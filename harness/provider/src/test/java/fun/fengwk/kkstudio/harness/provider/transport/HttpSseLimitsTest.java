package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 校验 HttpSseLimits 与 ServerSentEvent 基础数据载体与边界限制。 */
class HttpSseLimitsTest {

  @Test
  void validLimitsCreateSuccess() {
    HttpSseLimits limits = new HttpSseLimits(1024, 2048, 4096, 512);
    assertEquals(1024, limits.maxLineBytes());
    assertEquals(2048, limits.maxEventBytes());
    assertEquals(4096, limits.maxSuccessBodyBytes());
    assertEquals(512, limits.maxErrorBodyBytes());
  }

  @Test
  void illegalLimitsThrowException() {
    assertThrows(IllegalArgumentException.class, () -> new HttpSseLimits(0, 10, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> new HttpSseLimits(10, 0, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> new HttpSseLimits(10, 10, 0, 10));
    assertThrows(IllegalArgumentException.class, () -> new HttpSseLimits(10, 10, 10, 0));
  }

  @Test
  void serverSentEventEqualsAndHashCodeAndToString() {
    ServerSentEvent e1 = new ServerSentEvent("msg", "hello");
    ServerSentEvent e2 = new ServerSentEvent("msg", "hello");
    ServerSentEvent e3 = new ServerSentEvent("err", "world");

    assertEquals(e1, e2);
    assertEquals(e1.hashCode(), e2.hashCode());
    assertNotEquals(e1, e3);
    assertNotEquals(e1, null);
    assertNotEquals(e1, "other");

    assertTrue(e1.toString().contains("msg"));
    assertTrue(e1.toString().contains("dataLength=5"));
  }
}
