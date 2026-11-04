package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP 请求与响应对象的 toString 不输出 header、body 或 query。 */
class MavisHttpMessageRedactionTest {

  private static final String SECRET = "secret-token-value";

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
