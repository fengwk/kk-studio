package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** PluginResourceUnavailableException 是资源端口的确定性失败信号，测试锁定消息与原因被保留。 */
class PluginResourceUnavailableExceptionTest {

  @Test
  void preservesMessageAndCause() {
    RuntimeException cause = new IllegalStateException("cause");

    PluginResourceUnavailableException exception =
        new PluginResourceUnavailableException("unavailable", cause);

    assertEquals("unavailable", exception.getMessage());
    assertSame(cause, exception.getCause());
    assertEquals(
        "port unavailable",
        new PluginResourceUnavailableException("port unavailable").getMessage());
  }
}
