package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 诊断去敏：请求 URL、header、body 与响应 body 都不能通过 toString 或错误消息泄露凭据。 */
class MavisRedactionTest {

  private static final String SECRET = "secret-token-value";

  /** 已知秘密被替换为占位文本，空秘密不影响原文。 */
  @Test
  void redactsKnownSecrets() {
    assertEquals(
        "token " + MavisRedaction.REDACTED + " and " + MavisRedaction.REDACTED,
        MavisRedaction.redact("token " + SECRET + " and " + SECRET, SECRET));
    assertEquals("plain", MavisRedaction.redact("plain", ""));
    assertEquals("plain", MavisRedaction.redact("plain", (String) null));
    assertEquals("", MavisRedaction.redact(null, SECRET));
  }

  /** 安全 URL 只保留 scheme、host、port 与 path。 */
  @Test
  void degradesUrlsWithoutCredentials() {
    assertEquals(
        "https://agent.minimaxi.com/v1/api/user/renewal",
        MavisRedaction.safeUrl(
            "https://user:pass@agent.minimaxi.com/v1/api/user/renewal?token=" + SECRET + "#x"));
    assertEquals(
        "http://localhost:8080/path", MavisRedaction.safeUrl("http://localhost:8080/path?a=b"));
    assertEquals("<invalid URL>", MavisRedaction.safeUrl("not a url"));
    assertEquals("<invalid URL>", MavisRedaction.safeUrl("/relative/path"));
    assertEquals("<invalid URL>", MavisRedaction.safeUrl(null));
  }

  /** 诊断文本同时退化内嵌 URL 与替换已知秘密，覆盖 http(s) 与 deep-link 两种形态。 */
  @Test
  void diagnosticsDegradeEmbeddedUrlsAndSecrets() {
    String detail =
        MavisRedaction.diagnostic(
            "failed at https://agent.minimaxi.com/x?token="
                + SECRET
                + " and minimax-cn://auth-callback?accessToken="
                + SECRET,
            SECRET);

    assertFalse(detail.contains(SECRET));
    assertFalse(detail.contains("token="));
    assertTrue(detail.contains("https://agent.minimaxi.com/x"));
    assertTrue(detail.contains("minimax-cn://auth-callback"));
  }

  /** 请求与响应对象的 toString 不输出 header、body 或 query。 */
  @Test
  void requestAndResponseNeverExposeSecrets() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + SECRET);
    MavisHttpRequest request =
        new MavisHttpRequest(
            "POST",
            "https://agent.minimaxi.com/v1/api/user/renewal?token=" + SECRET,
            headers,
            "{\"token\":\"" + SECRET + "\"}",
            Duration.ofSeconds(30));

    assertFalse(request.toString().contains(SECRET));
    assertTrue(request.toString().contains("http://") || request.toString().contains("https://"));

    MavisHttpResponse response = new MavisHttpResponse(200, "{\"token\":\"" + SECRET + "\"}");
    assertFalse(response.toString().contains(SECRET));
    assertFalse(response.toString().contains("token"));
  }
}
