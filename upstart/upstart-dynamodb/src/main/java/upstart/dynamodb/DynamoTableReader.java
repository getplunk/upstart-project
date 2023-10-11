package upstart.dynamodb;

import com.google.common.collect.MoreCollectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.NestedAttributeName;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.model.CreateTableEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import upstart.aws.FluxPromise;
import upstart.util.concurrent.ListPromise;
import upstart.util.concurrent.OptionalPromise;
import upstart.util.concurrent.Promise;
import upstart.util.functions.MoreFunctions;
import upstart.util.reflect.Reflect;
import upstart.util.strings.NamingStyle;

import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;

public interface DynamoTableReader<B, T> extends DynamoTableMapper {
  Consumer<QueryEnhancedRequest.Builder> queryDecorator();

  ItemExtractor<B, T> extractor();
  DynamoTableDao<B, ?> dao();

  @Override
  TableSchema<B> tableSchema();

  default DynamoDbAsyncTable<B> enhancedTable() {
    return dao().enhancedTable();
  }

  default OptionalPromise<T> getItem(Consumer<Key.Builder> keyBuilder) {
    return getItem(MoreFunctions.tap(keyBuilder).apply(Key.builder()).build());
  }

  default OptionalPromise<T> getItem(Key key) {
    return OptionalPromise.ofFutureNullable(enhancedTable().getItem(key))
            .thenMapCompose(this::transform);
  }

  default Flux<T> query(
          Consumer<QueryEnhancedRequest.Builder> requestConsumer,
          NestedAttributeName... extraAttributes
  ) {
    return query(requestConsumer, Arrays.asList(extraAttributes));
  }

  default Flux<T> query(
          Consumer<QueryEnhancedRequest.Builder> requestConsumer,
          Collection<NestedAttributeName> extraAttributes
  ) {
    return toFlux(enhancedTable().query(
            b -> queryDecorator().andThen(requestConsumer).accept(b.addNestedAttributesToProject(extraAttributes))
    ));
  }

  default Flux<T> queryIndex(
          DynamoDbAsyncIndex<B> index,
          Consumer<QueryEnhancedRequest.Builder> requestConsumer,
          NestedAttributeName... extraAttributes
  ) {
    return queryIndex(index, requestConsumer, Arrays.asList(extraAttributes));
  }

  default Flux<T> queryIndex(
          DynamoDbAsyncIndex<B> index,
          Consumer<QueryEnhancedRequest.Builder> requestConsumer,
          Collection<NestedAttributeName> extraAttributes
  ) {
    return toFlux(
            index.query(b -> queryDecorator()
                    .andThen(requestConsumer)
                    .accept(b.addNestedAttributesToProject(extraAttributes)))
    );
  }

  default Flux<T> scan(Consumer<ScanEnhancedRequest.Builder> requestConsumer) {
    return toFlux(enhancedTable().scan(requestConsumer));
  }

  default Promise<T> transform(B blob) {
    return Promise.of(extractor().extract(blob));
  }

  default Flux<T> toFlux(SdkPublisher<Page<B>> pagePublisher) {
    return toFlux(PagePublisher.create(pagePublisher));
  }

  default Flux<T> toFlux(PagePublisher<B> pagePublisher) {
    return beanFlux(pagePublisher)
            .map(this::transform)
            .flatMap(Mono::fromFuture);
  }

  default <V> ListPromise<V> toList(Flux<V> items) {
    return FluxPromise.list(items);
  }

  @Override
  default Optional<String> getTtlAttribute() {
    return Reflect.allAnnotatedMethods(
                    beanClass(),
                    TimeToLiveAttribute.class,
                    Reflect.LineageOrder.SubclassBeforeSuperclass
            )
            .collect(MoreCollectors.toOptional())
            .map(meth -> Optional.ofNullable(meth.getAnnotation(DynamoDbAttribute.class))
                    .map(DynamoDbAttribute::value)
                    .orElseGet(() -> {
                      checkState(
                              meth.getName().startsWith("get"),
                              "Annotated bean method should start with 'get': %s",
                              meth.getName()
                      );
                      return NamingStyle.UpperCamelCase.convertTo(
                              NamingStyle.LowerCamelCase,
                              meth.getName().substring(3)
                      );
                    }));
  }

  default <V> DynamoTableReader<B, V> withReadTransformer(ItemTransformer<? super B, ? super T, V> transform) {
    return new BaseTableReader.Transformed<>(dao(), queryDecorator(), extractor().andThen(transform));
  }

  default DynamoTableReader<B, T> withQueryDecorator(Consumer<QueryEnhancedRequest.Builder> decorator) {
    return new BaseTableReader.Transformed<B, T>(dao(), queryDecorator().andThen(decorator), extractor());
  }

  default Flux<B> beanFlux(PagePublisher<B> pagePublisher) {
    return Flux.from(pagePublisher.items());
  }

  final class Transformed<B, T> extends BaseTableReader<B, T> {
    private final DynamoTableDao<B, ?> dao;

    Transformed(DynamoTableDao<B, ?> dao, Consumer<QueryEnhancedRequest.Builder> queryDecorator, ItemExtractor<B, T> extractor) {
      super(queryDecorator, extractor);
      this.dao = dao;
    }

    @Override
    public Class<?> beanClass() {
      return dao().beanClass();
    }

    @Override
    public CreateTableEnhancedRequest.Builder prepareCreateTableRequest(CreateTableEnhancedRequest.Builder builder) {
      return dao().prepareCreateTableRequest(builder);
    }

    @Override
    public TableSchema<B> tableSchema() {
      return dao().tableSchema();
    }

    @Override
    public DynamoTableDao<B, ?> dao() {
      return dao;
    }
  }
}
