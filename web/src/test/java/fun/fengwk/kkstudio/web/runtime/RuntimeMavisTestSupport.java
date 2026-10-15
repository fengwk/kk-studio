package fun.fengwk.kkstudio.web.runtime;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** 从运行期 JAR 装配 Mavis，并以禁止出网的传输验证启动和恢复，不建立编译期插件依赖。 */
final class RuntimeMavisTestSupport {

  private RuntimeMavisTestSupport() {}

  static ApplicationContextRunner withMavis(
      ApplicationContextRunner runner, AtomicInteger requests) {
    return withMavis(
        runner,
        request -> {
          requests.incrementAndGet();
          throw new AssertionError("plugin startup and restore must not issue remote requests");
        });
  }

  /** 仅替换传输边界；请求和响应都保持运行期类型，不引入可选插件的编译期依赖。 */
  static ApplicationContextRunner withMavis(
      ApplicationContextRunner runner, RequestHandler handler) {
    String prefix = "fun.fengwk.kkstudio.plugin.";
    try {
      Class<?> configuration = Class.forName(prefix + "minimaxmavis.MiniMaxMavisAutoConfiguration");
      Class<?> transportType = Class.forName(prefix + "minimaxmavis.MavisHttpTransport");
      Object transport =
          Proxy.newProxyInstance(
              transportType.getClassLoader(),
              new Class<?>[] {transportType},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "send" -> handler.send(args[0]);
                    case "toString" -> "offline Mavis transport";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
      return runner
          .withInitializer(
              context ->
                  context.getBeanFactory().registerSingleton("mavisHttpTransport", transport))
          .withConfiguration(AutoConfigurations.of(configuration));
    } catch (ClassNotFoundException error) {
      throw new AssertionError("the Mavis plugin must be on the web test runtime classpath", error);
    }
  }

  @FunctionalInterface
  interface RequestHandler {
    Object send(Object request) throws Exception;
  }
}
