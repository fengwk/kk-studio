package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
  void defaultLimitsValues() {
    assertEquals(1024 * 1024, HttpSseLimits.DEFAULT.maxLineBytes());
    assertEquals(1024 * 1024, HttpSseLimits.DEFAULT.maxEventBytes());
    assertEquals(128L * 1024 * 1024, HttpSseLimits.DEFAULT.maxSuccessBodyBytes());
    assertEquals(64 * 1024, HttpSseLimits.DEFAULT.maxErrorBodyBytes());
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

    assertTrue(e1.toString().contains("eventLength=3"));
    assertTrue(e1.toString().contains("dataLength=5"));
    assertFalse(e1.toString().contains("msg"));
    assertFalse(e1.toString().contains("hello"));
  }

  @Test
  void transportExceptionConstructorsAndMethods() {
    TransportException ex1 = new TransportException(TransportErrorKind.IO, null);
    assertEquals(TransportErrorKind.IO, ex1.kind());
    assertNull(ex1.getMessage());

    TransportException ex2 =
        new TransportException(
            TransportErrorKind.HTTP_STATUS,
            "failed",
            500,
            "error body".getBytes(StandardCharsets.UTF_8),
            Map.of("h", List.of("v")),
            null);
    assertEquals(500, ex2.statusCode());
    assertEquals("error body", ex2.errorBodyUtf8());
    assertFalse(ex2.isErrorBodyTruncated());

    Throwable safeCause = ex2.getCause();
    TransportException ex3 = new TransportException(TransportErrorKind.IO, "msg", safeCause);
    assertEquals(safeCause, ex3.getCause());
  }
}
