package upstart.json;

import upstart.UpstartDeploymentStage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@PermittedDeploymentStages(stages = {UpstartDeploymentStage.dev, UpstartDeploymentStage.stage, UpstartDeploymentStage.test})
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface NonProdOnly {}
