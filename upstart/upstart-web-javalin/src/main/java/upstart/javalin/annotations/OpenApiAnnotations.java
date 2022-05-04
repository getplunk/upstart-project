package upstart.javalin.annotations;

import com.google.common.collect.ObjectArrays;
import io.javalin.plugin.openapi.annotations.OpenApi;
import io.javalin.plugin.openapi.annotations.OpenApiContent;
import io.javalin.plugin.openapi.annotations.OpenApiResponse;
import io.javalin.plugin.openapi.annotations.OpenApiSecurity;
import org.immutables.value.Value;

import java.util.Optional;

@Value.Include({OpenApi.class, OpenApiResponse.class, OpenApiContent.class, OpenApiSecurity.class})
public interface OpenApiAnnotations {
  String DEFAULT_STATUS = "200";

  static OpenApi openApi(Optional<OpenApi> providedAnnotation, OpenApiSecurity[] securities, OpenApiResponse response) {
    ImmutableOpenApi.Builder builder = ImmutableOpenApi.builder();
    providedAnnotation.ifPresentOrElse(
            provided -> builder.from(provided)
                    .security(ObjectArrays.concat(securities, provided.security(), OpenApiSecurity.class))
                    .responses(ObjectArrays.concat(response, provided.responses())),
            () -> builder.responses(response).security(securities));
    return builder.build();
  }

  static ImmutableOpenApiResponse.Builder responseBuilder() {
    return ImmutableOpenApiResponse.builder();
  }

  static OpenApiSecurity security(String scheme, String... scopes) {
    return ImmutableOpenApiSecurity.builder()
                   .name(scheme)
                   .scopes(scopes)
                   .build();
  }

  static ImmutableOpenApiContent.Builder contentBuilder() {
    return ImmutableOpenApiContent.builder();
  }
}
