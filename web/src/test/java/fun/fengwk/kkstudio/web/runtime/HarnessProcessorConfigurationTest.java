package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.util.Arrays;

/** 组合根 destroy hook 契约：Model 执行器不能由 Spring 在显式停机预算之外无界等待。 */
class HarnessProcessorConfigurationTest {

  @Test
  void modelExecutionExecutorUsesNonBlockingShutdownDestroyHook() {
    Method factory =
        Arrays.stream(HarnessProcessorConfiguration.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("harnessModelExecutionExecutor"))
            .findFirst()
            .orElseThrow();

    Bean bean = factory.getAnnotation(Bean.class);

    assertEquals("shutdown", bean.destroyMethod());
  }
}
