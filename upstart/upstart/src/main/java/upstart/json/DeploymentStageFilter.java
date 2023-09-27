package upstart.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import upstart.config.UpstartContext;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Optional;

public class DeploymentStageFilter implements JsonPropertyFilter {

  private final UpstartContext context;
  public final static String ID = "DeploymentStageFilter";

  @Inject
  public DeploymentStageFilter(UpstartContext context) {
    this.context = context;
  }

  private boolean include(PropertyWriter propertyWriter) {
    var annotation = findAnnotation(propertyWriter);
    if (annotation.isEmpty()) {
      return true;
    } else {
      return annotation.stream()
              .flatMap(a -> Arrays.stream(a.stages()))
              .anyMatch(stage -> stage.equals(context.deploymentStage()));
    }
  }

  private Optional<PermittedDeploymentStages> findAnnotation(PropertyWriter propertyWriter) {
    var annotation = propertyWriter.findAnnotation(PermittedDeploymentStages.class);
    if (annotation != null) {
      return Optional.of(annotation);
    }
    // check method-level annotation for meta-annotations
    for (var a : propertyWriter.getMember().getAllAnnotations().annotations()) {
      annotation = a.annotationType().getAnnotation(PermittedDeploymentStages.class);
      if (annotation != null) {
        return Optional.of(annotation);
      }
    }
    return Optional.empty();
  }

  @Override
  public void serializeAsField(
          Object o,
          JsonGenerator jsonGenerator,
          SerializerProvider serializerProvider,
          PropertyWriter propertyWriter
  ) throws Exception {
    if (include(propertyWriter)) {
      propertyWriter.serializeAsField(o, jsonGenerator, serializerProvider);
    }
  }

  @Override
  public void serializeAsElement(
          Object o,
          JsonGenerator jsonGenerator,
          SerializerProvider serializerProvider,
          PropertyWriter propertyWriter
  ) throws Exception {
    if (include(propertyWriter)) {
      propertyWriter.serializeAsElement(o, jsonGenerator, serializerProvider);
    }
  }

  @Deprecated
  @Override
  public void depositSchemaProperty(
          PropertyWriter propertyWriter,
          ObjectNode objectNode,
          SerializerProvider serializerProvider
  ) throws JsonMappingException {
    if (include(propertyWriter)) {
      propertyWriter.depositSchemaProperty(objectNode, serializerProvider);
    }
  }

  @Override
  public void depositSchemaProperty(
          PropertyWriter propertyWriter,
          JsonObjectFormatVisitor jsonObjectFormatVisitor,
          SerializerProvider serializerProvider
  ) throws JsonMappingException {
    if (include(propertyWriter)) {
      propertyWriter.depositSchemaProperty(jsonObjectFormatVisitor, serializerProvider);
    }
  }

  @Override
  public String id() {
    return ID;
  }
}
