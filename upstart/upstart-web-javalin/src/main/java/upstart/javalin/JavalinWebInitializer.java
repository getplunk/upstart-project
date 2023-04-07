package upstart.javalin;

import io.javalin.config.JavalinConfig;

@FunctionalInterface
public interface JavalinWebInitializer {
  void initializeWeb(JavalinConfig config);
}
