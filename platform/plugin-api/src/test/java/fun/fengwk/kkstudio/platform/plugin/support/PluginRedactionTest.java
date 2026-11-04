package fun.fengwk.kkstudio.platform.plugin.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 诊断去敏：请求 URL、header 与 body 都不能通过错误消息泄露凭据。 */
class PluginRedactionTest {

  private static final String SECRET = "secret-token-value";

  /** 已知秘密被替换为占位文本，空秘密不影响原文。 */
  @Test
  void redactsKnownSecrets() {
    assertEquals(
        "token " + PluginRedaction.REDACTED + " and " + PluginRedaction.REDACTED,
        PluginRedaction.redact("token " + SECRET + " and " + SECRET, SECRET));
    assertEquals("plain", PluginRedaction.redact("plain", ""));
    assertEquals("plain", PluginRedaction.redact("plain", (String) null));
    assertEquals("", PluginRedaction.redact(null, SECRET));
  }

  /** 安全 URL 只保留 scheme、host、port 与 path。 */
  @Test
  void degradesUrlsWithoutCredentials() {
    assertEquals(
        "https://agent.minimaxi.com/v1/api/user/renewal",
        PluginRedaction.safeUrl(
            "https://user:pass@agent.minimaxi.com/v1/api/user/renewal?token=" + SECRET + "#x"));
    assertEquals(
        "http://localhost:8080/path", PluginRedaction.safeUrl("http://localhost:8080/path?a=b"));
    assertEquals("<invalid URL>", PluginRedaction.safeUrl("not a url"));
    assertEquals("<invalid URL>", PluginRedaction.safeUrl("/relative/path"));
    assertEquals("<invalid URL>", PluginRedaction.safeUrl(null));
  }

  /** 诊断文本同时退化内嵌 URL 与替换已知秘密，覆盖 http(s) 与 deep-link 两种形态。 */
  @Test
  void diagnosticsDegradeEmbeddedUrlsAndSecrets() {
    String detail =
        PluginRedaction.diagnostic(
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
}
