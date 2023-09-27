package upstart.json;

import upstart.UpstartDeploymentStage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
public @interface PermittedDeploymentStages {
  UpstartDeploymentStage[] stages();
}

