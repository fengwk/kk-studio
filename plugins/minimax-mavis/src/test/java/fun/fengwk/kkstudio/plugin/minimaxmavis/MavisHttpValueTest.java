package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 请求与响应值对象的结构约束：只接受可发送的请求形状，并防止外部改动影响已构造的请求。 */
class MavisHttpValueTest {

  /** header 在构造时被复制，调用方后续修改不会改变已构造的请求。 */
  @Test
  void copiesHeadersAndExposesRequestFields() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    MavisHttpRequest request =
        new MavisHttpRequest(
            "POST", "https://agent.minimaxi.com/x", headers, "{}", Duration.ofSeconds(5));

    headers.put("Content-Type", "text/plain");
    headers.put("token", "late");

    assertEquals("application/json", request.headers().get("Content-Type"));
    assertEquals(1, request.headers().size());
    assertEquals("POST", request.method());
    assertEquals("{}", request.body());
    assertEquals(Duration.ofSeconds(5), request.timeout());
    assertThrows(UnsupportedOperationException.class, () -> request.headers().put("x", "y"));
  }

  /** 方法、URL 与超时必须构成一个可发送的请求。 */
  @Test
  void rejectsUnsendableRequests() {
    assertThrows(
        MavisValidationException.class,
        () -> new MavisHttpRequest(" ", "https://x/y", Map.of(), null, Duration.ofSeconds(1)));
    assertThrows(
        MavisValidationException.class,
        () -> new MavisHttpRequest("GET", " ", Map.of(), null, Duration.ofSeconds(1)));
    assertThrows(
        MavisValidationException.class,
        () -> new MavisHttpRequest("GET", "https://x/y", Map.of(), null, Duration.ZERO));
    assertThrows(
        MavisValidationException.class,
        () -> new MavisHttpRequest("GET", "https://x/y", Map.of(), null, Duration.ofSeconds(-1)));
  }

  /** 响应只接受合法状态码，缺失 body 归一化为空字符串。 */
  @Test
  void normalizesResponseBodyAndValidatesStatus() {
    MavisHttpResponse response = new MavisHttpResponse(204, null);

    assertEquals(204, response.statusCode());
    assertEquals("", response.body());
    assertThrows(MavisValidationException.class, () -> new MavisHttpResponse(99, "{}"));
    assertThrows(MavisValidationException.class, () -> new MavisHttpResponse(600, "{}"));
  }
}
