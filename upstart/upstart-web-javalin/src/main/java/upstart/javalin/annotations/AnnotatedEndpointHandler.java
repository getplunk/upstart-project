package upstart.javalin.annotations;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.base.Defaults;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import com.google.common.primitives.Primitives;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpCode;
import io.javalin.openapi.AnnotationApiMappingKt;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiResponse;
import io.javalin.plugin.openapi.dsl.OpenApiBuilder;
import io.javalin.plugin.openapi.dsl.OpenApiDocumentation;
import io.javalin.plugin.openapi.dsl.OpenApiUpdater;
import io.javalin.plugin.openapi.dsl.OpenApiUpdaterKt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import upstart.javalin.UnprocessableEntityResponse;
import upstart.proxy.Proxies;
import upstart.util.collect.PairStream;
import upstart.util.concurrent.LazyReference;
import upstart.util.concurrent.ThreadLocalReference;
import upstart.util.exceptions.Exceptions;
import upstart.util.reflect.Modifiers;
import upstart.util.reflect.Reflect;
import upstart.util.strings.MoreStrings;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class AnnotatedEndpointHandler<T> {
  private static final Logger LOG = LoggerFactory.getLogger(AnnotatedEndpointHandler.class);
  public static final String OBJECT_MAPPER_ATTRIBUTE = "ObjectMapper";
  private final Map<Method, Endpoint> endpoints;
  private final Class<T> type;
  private final LazyReference<RouteProxyInterceptor> routeProxy = LazyReference.from(RouteProxyInterceptor::new);

  AnnotatedEndpointHandler(Class<T> type, HttpRegistry registry) {
    this.type = type;
    var classSecurityConstraints = registry.getSecurityConstraints(type);

    endpoints = PairStream.withMappedValues(
            Reflect.allAnnotatedMethods(
                    type,
                    Http.class,
                    Reflect.LineageOrder.SuperclassBeforeSubclass
            ), method -> {
              Http annotation = method.getAnnotation(Http.class);
              return new Endpoint(
                      method,
                      annotation.method().handlerType,
                      annotation.path(),
                      annotation.successCode(),
                      annotation.responseDoc(),
                      annotation.hideApiDoc(),
                      classSecurityConstraints.merge(registry.getSecurityConstraints(method))
              );
            }).toImmutableMap();
    checkArgument(!endpoints.isEmpty(), "No @Http endpoint-methods found in class: %s", type);
  }


  public void installHandlers(T target, Javalin javalin) {
    for (Endpoint endpoint : endpoints.values()) {
      endpoint.register(javalin, target);
    }
  }

  public HttpUrl urlFor(Consumer<? super T> methodInvoker) {
    return routeProxy.get().capture(methodInvoker);
  }

  private static class Endpoint {
    private final Method method;
    private final HandlerType handlerType;
    private final String path;
    private final SecurityConstraints securityConstraints;
    private final List<ParamResolver> paramResolvers;
    private final BiConsumer<Context, Object> resultDispatcher;
    private final OpenApiDocumentation documentation;
    private boolean mappedBody = false;

    private Endpoint(
            Method method,
            HandlerType handlerType,
            String path,
            HttpCode annotationSuccessCode,
            OpenApiResponse openApiResponse,
            boolean hideApiDoc,
            SecurityConstraints securityConstraints
    ) {
      checkArgument(
              Modifiers.Public.matches(method),
              "@Http method %s.%s must be public",
              method.getDeclaringClass().getSimpleName(),
              method.getName()
      );
      this.method = method;
      paramResolvers = Arrays.stream(method.getParameters())
              .map(this::buildResolver)
              .collect(ImmutableList.toImmutableList());
      this.handlerType = handlerType;
      this.path = path;
      this.securityConstraints = securityConstraints;
      ImmutableOpenApiResponse.Builder apiResponse = OpenApiAnnotations.responseBuilder().from(openApiResponse);
      int successStatus = reconcileSuccessStatus(
              method,
              annotationSuccessCode,
              apiResponse,
              Integer.parseInt(openApiResponse.status())
      );
      BiConsumer<Context, Object> assignStatus = successStatus == 200
              ? (ctx, o) -> {}
              : (ctx, o) -> {
                if (ctx.status() == 200) ctx.status(successStatus);
              };
      BiConsumer<Context, ?> responder;
      Class<?> returnType = method.getReturnType();
      OpenApiContent[] openApiContent;
      if (returnType == void.class) {
        openApiContent = openApiResponse.content();
        responder = assignStatus;
      } else if (InputStream.class.isAssignableFrom(returnType)) {
        // TODO: is this correct?
        openApiContent = openApiContent(byte[].class, ContentType.OCTET_STREAM, openApiResponse);
        responder = ((BiConsumer<Context, InputStream>) Context::result).andThen(assignStatus);
      } else if (CompletionStage.class.isAssignableFrom(returnType)) {
        // TODO: deal with further generics, arrays, etc
        Class<?> futureType = Reflect.getFirstGenericType(method.getGenericReturnType());
        openApiContent = openApiContent(futureType, ContentType.JSON, openApiResponse);
        responder = (Context context, CompletionStage<?> o) -> context.future(
                o.toCompletableFuture()
                        .whenComplete((ignored, e) -> {
                          if (context.status() == 200) {
                            if (e == null) {
                              assignStatus.accept(context, ignored);
                            } else if (!(e instanceof Exception)){
                              // javalin responds with 200 if the future is completed with an Error!?
                              LOG.error("Unexpected error", e);
                              context.status(500);
                            }
                          }
                        }));
      } else {
        openApiContent = openApiContent(returnType, ContentType.JSON, openApiResponse);
        responder = ((BiConsumer<Context, Object>) Context::json).andThen(assignStatus);
      }

      documentation = hideApiDoc
              ? OpenApiAnnotations.DOCUMENTATION_IGNORE
              : buildDocumentation(apiResponse.content(openApiContent).build());
      //noinspection unchecked
      resultDispatcher = (BiConsumer<Context, Object>) responder;
    }

    private static int reconcileSuccessStatus(
            Method method,
            HttpCode annotationSuccessCode,
            ImmutableOpenApiResponse.Builder apiResponse,
            int docResponseStatus
    ) {
      int successStatus;
      int annotationSuccessStatus = annotationSuccessCode.getStatus();
      if (docResponseStatus != annotationSuccessStatus) {
        if (docResponseStatus == 200) {
          apiResponse.status(Integer.toString(annotationSuccessStatus));
          successStatus = annotationSuccessStatus;
        } else if (annotationSuccessStatus == 200) {
          successStatus = docResponseStatus;
        } else {
          throw new IllegalArgumentException(
                  String.format(
                          "Method %s.%s has conflicting @Http.successStatus and @OpenApiResponse.status values: %s and %s",
                          method.getDeclaringClass().getSimpleName(),
                          method.getName(),
                          annotationSuccessStatus,
                          docResponseStatus
                  )
          );
        }
      } else {
        successStatus = annotationSuccessStatus;
      }
      return successStatus;
    }

    private static OpenApiContent[] openApiContent(
            Class<?> from,
            String contentType,
            OpenApiResponse providedResponse
    ) {
      if (Primitives.unwrap(from) == void.class) return providedResponse.content();
      ImmutableOpenApiContent addedContent = OpenApiAnnotations.contentBuilder().from(from).type(contentType).build();
      return ObjectArrays.concat(addedContent, providedResponse.content());
    }

    private OpenApiDocumentation buildDocumentation(OpenApiResponse openApiResponse) {
      OpenApi compositeOpenApi = OpenApiAnnotations.openApi(
              Optional.ofNullable(method.getAnnotation(OpenApi.class)),
              securityConstraints.securityArray(),
              openApiResponse
      );

      OpenApiDocumentation documentation = AnnotationApiMappingKt.asOpenApiDocumentation(compositeOpenApi);

      OpenApiUpdaterKt.applyAllUpdates(paramResolvers, documentation);

      return documentation;
    }

    public void register(Javalin javalin, Object target) {
      LOG.info(
              "Registered route {}[{}] => {}.{}(...)",
              handlerType,
              path,
              method.getDeclaringClass().getSimpleName(),
              method.getName()
      );
      Handler handler = OpenApiBuilder.documented(documentation, (Handler) ctx -> invoke(target, ctx));
      javalin.addHandler(handlerType, path, handler, securityConstraints.roleArray());
    }

    void invoke(Object target, Context ctx) throws Exception {
      var args = new Object[paramResolvers.size()];
      for (int i = 0; i < paramResolvers.size(); i++) {
        args[i] = paramResolvers.get(i).resolve(ctx);
      }

      try {
        Object result = method.invoke(target, args);
        resultDispatcher.accept(ctx, result);
      } catch (InvocationTargetException e) {
        Throwable cause = e.getCause();
        Throwables.throwIfInstanceOf(cause, Exception.class);
        throw e;
      }
    }

    public HttpUrl buildUrl(Object... args) {
      assert args.length == paramResolvers.size();
      var builder = new UrlBuilder(path);
      for (int i = 0; i < args.length; i++) {
        paramResolvers.get(i).applyToUrl(builder, args[i]);
      }
      return builder.build();
    }

    private ParamResolver buildResolver(Parameter parameter) {
      Class<?> paramType = parameter.getType();
      if (paramType == Context.class) {
        return ParamResolver.nonUrlParam(parameter, Optional.empty(), ctx -> ctx);
      } else if (parameter.isAnnotationPresent(PathParam.class)) {
        return UrlParamStrategy.Path.resolver(parameter);
      } else if (parameter.isAnnotationPresent(QueryParam.class)) {
        return UrlParamStrategy.Query.resolver(parameter);
      } else if (parameter.isAnnotationPresent(Session.class)) {
        String name = paramName(parameter.getAnnotation(Session.class).value(), parameter);
        return ParamResolver.nonUrlParam(parameter, Optional.empty(), ctx -> checkNotNull(ctx.sessionAttribute(name), "missing session attribute '%s' for %s", name, this));
      } else if (parameter.isAnnotationPresent(Request.class)) {
        String name = paramName(parameter.getAnnotation(Request.class).value(), parameter);
        return ParamResolver.nonUrlParam(parameter, Optional.empty(), ctx -> checkNotNull(ctx.attribute(name), "missing request attribute '%s' for %s", name, this));
      } else {
        checkArgument(!mappedBody, "Method has multiple unannotated parameters", method);
        mappedBody = true;
        if (paramType == String.class) {
          return ParamResolver.nonUrlParam(parameter, Optional.of(paramType), Context::body);
        } else if (paramType == byte[].class) {
          return ParamResolver.nonUrlParam(parameter, Optional.of(paramType), Context::bodyAsBytes);
        } else if (paramType == InputStream.class) {
          return ParamResolver.nonUrlParam(parameter, Optional.of(byte[].class), Context::bodyAsInputStream);
        } else {
          JavaType valueType = TypeFactory.defaultInstance().constructType(parameter.getParameterizedType());
          return ParamResolver.nonUrlParam(parameter, Optional.of(paramType), ctx -> {
            try {
              return objectMapper(ctx).readValue(ctx.bodyAsInputStream(), valueType);
            } catch (Exception e) {
              if (LOG.isDebugEnabled()) {
                Executable exe = parameter.getDeclaringExecutable();
                String method = exe.getDeclaringClass().getName() + "." + exe.getName();
                LOG.debug("Failed to parse body for parameter {}({})", method, parameter, e);
              }

              if (e instanceof JsonMappingException jme) {
                throw new UnprocessableEntityResponse(jme);
              } else {
                throw Exceptions.throwUnchecked(e);
              }
            }
          });
        }
      }
    }

    private static ObjectMapper objectMapper(Context ctx) {
      return ctx.appAttribute(OBJECT_MAPPER_ATTRIBUTE);
    }

    private static String paramName(String annotatedName, Parameter param) {
      if (annotatedName.isEmpty()) {
        String name = param.getName();
        checkState(
                param.isNamePresent(),
                "Parameter-name unavailable for %s parameter %s (method %s)",
                param.getType().getSimpleName(),
                name,
                param.getDeclaringExecutable().getName()
        );
        return MoreStrings.toLowerSnakeCase(name);
      } else {
        return annotatedName;
      }
    }

    @Override
    public String toString() {
      return "Endpoint{" + handlerType +
              " " + path +
              ", method=" + method +
              '}';
    }

    private static class ParamResolver implements OpenApiUpdater<OpenApiDocumentation> {
      private static final OpenApiUpdater<OpenApiDocumentation> NO_DOCUMENTATION = ignored -> {
      };

      private final String paramName;
      private final RouteParamFormatter paramFormatter;
      private final OpenApiUpdater<OpenApiDocumentation> documentation;
      private final Function<Context, ?> resolver;

      ParamResolver(
              String paramName,
              RouteParamFormatter paramFormatter,
              Function<Context, ?> resolver,
              OpenApiUpdater<OpenApiDocumentation> documentation
      ) {
        this.paramName = paramName;
        this.paramFormatter = paramFormatter;
        this.documentation = documentation;
        this.resolver = resolver;
      }

      public static ParamResolver nonUrlParam(
              Parameter parameter,
              Optional<Class<?>> bodyType,
              Function<Context, Object> resolver
      ) {
        OpenApiUpdater<OpenApiDocumentation> documentation = bodyType
                .map(type -> (OpenApiUpdater<OpenApiDocumentation>) doc -> doc.body(type, ContentType.JSON))
                .orElse(NO_DOCUMENTATION);
        return new ParamResolver(parameter.getName(), RouteParamFormatter.NonUrl, resolver, documentation);
      }

      public Object resolve(Context context) {
        return resolver.apply(context);
      }

      public void applyToUrl(UrlBuilder urlBuilder, Object value) {
        paramFormatter.applyToUrl(urlBuilder, paramName, value);
      }

      @Override
      public void applyUpdates(OpenApiDocumentation documentation) {
        this.documentation.applyUpdates(documentation);
      }
    }

    private interface RouteParamFormatter {
      RouteParamFormatter NonUrl = (urlBuilder, paramName, value) -> checkArgument(
              value == null || value.equals(Defaults.defaultValue(value.getClass())),
              "Non-null/default value passed to HttpRoutes proxy-method for parameter '%s'",
              paramName
      );

      void applyToUrl(UrlBuilder urlBuilder, String paramName, Object value);
    }

    private enum UrlParamStrategy implements RouteParamFormatter {
      Path,
      Query;

      ParamResolver resolver(Parameter parameter) {
        String name = paramName(extractParamName(parameter), parameter);
        // TODO: javalin-openapi cannot support generic parameters such as Optional<>, because its DocumentedParameter takes Class rather than Type
        boolean nullable = parameter.isAnnotationPresent(Nullable.class);
        Function<Context, String> stringExtractor = nullable
                ? ctx -> getString(ctx, name)
                : ctx -> checkNull(name, getString(ctx, name));

        Function<Context, ?> resolver;
        Class<?> type = parameter.getType();
        if (type == String.class) {
          resolver = stringExtractor;
        } else {
          var javaType = TypeFactory.defaultInstance().constructType(parameter.getParameterizedType());
          resolver = ctx -> {
            try {
              return objectMapper(ctx).convertValue(stringExtractor.apply(ctx), javaType);
            } catch (IllegalArgumentException e) {
              // TODO: consider having param-resolvers return a validation-result, to support reporting multiple errors
              throw new BadRequestResponse("Bad request", Map.of(name, e.getMessage()));
            }
          };
        }
        return new ParamResolver(name, this, resolver, apiUpdater(name, type));
      }

      private static <T> T checkNull(String name, T value) {
        if (value != null) {
          return value;
        } else {
          throw new BadRequestResponse("Missing required parameter '" + name + "'");
        }
      }

      @Override
      public void applyToUrl(UrlBuilder urlBuilder, String paramName, Object value) {
        switch (this) {
          case Path -> urlBuilder.withPathParam(paramName, value);
          case Query -> urlBuilder.withQueryParam(paramName, value);
        }
      }

      private String getString(Context context, String paramName) {
        return switch (this) {
          case Path -> context.pathParam(paramName);
          case Query -> context.queryParam(paramName);
        };
      }

      private String extractParamName(Parameter parameter) {
        return switch (this) {
          case Path -> parameter.getAnnotation(PathParam.class).value();
          case Query -> parameter.getAnnotation(QueryParam.class).value();
        };
      }

      private OpenApiUpdater<OpenApiDocumentation> apiUpdater(String name, Class<?> paramType) {
        return switch (this) {
          case Path -> doc -> doc.pathParam(name, paramType);
          case Query -> doc -> doc.queryParam(name, paramType);
        };
      }
    }
  }

  class RouteProxyInterceptor implements InvocationHandler {
    final T proxy = Proxies.createProxy(type, this);
    final ThreadLocalReference<HttpUrl> requestedRoute = new ThreadLocalReference<>();


    public HttpUrl capture(Consumer<? super T> invoker) {
      try {
        invoker.accept(proxy);
        return checkNotNull(requestedRoute.get(), "No proxy-method was invoked");
      } finally {
        requestedRoute.remove();
      }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      HttpUrl path = endpoints.get(method).buildUrl(args);
      checkState(requestedRoute.getAndSet(path) == null);
      return null;
    }
  }
}