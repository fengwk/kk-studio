package fun.fengwk.kkstudio.web.runtime;

import static org.mockito.Mockito.mock;

import org.mockito.Answers;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
          mock(
              transportType,
              invocation ->
                  invocation.getMethod().getName().equals("send")
                      ? handler.send(invocation.getArgument(0))
                      : Answers.RETURNS_DEFAULTS.answer(invocation));
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
