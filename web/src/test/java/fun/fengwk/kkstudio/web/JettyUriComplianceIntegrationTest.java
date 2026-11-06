package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 验证 Jetty 默认 URI 合规行为：合法应用与 code-server 路径到达应用层，歧义路径（含空段 // 或编码斜杠 %2F）由容器以 400 拒绝。 */
class JettyUriComplianceIntegrationTest extends WebPostgresTestSupport {

  private static final String ENV_ID = "11111111-1111-1111-1111-111111111111";
  private static final String VIEW_ID = "22222222-2222-2222-2222-222222222222";

  @LocalServerPort private int port;

  private final HttpClient client = HttpClient.newHttpClient();

  private HttpResponse<String> send(String rawPathAndQuery) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPathAndQuery))
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/platform/settings/schema",
        "/api/platform/settings/schema?msg=hello%20world",
        "/api/platform/settings/schema;jsessionid=testsession123"
      })
  void applicationRoutesReachApplication(String path) throws Exception {
    HttpResponse<String> resp = send(path);
    assertEquals(200, resp.statusCode(), "合法应用探活路由应到达应用层并返回 200: " + path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/environment-ide/"
            + ENV_ID
            + "/"
            + VIEW_ID
            + "/stable-hash1234/static/out/vs/code/browser/workbench/workbench.js",
        "/environment-ide/"
            + ENV_ID
            + "/"
            + VIEW_ID
            + "/stable-hash1234/static/out/vs/code%20space/workbench.js",
        "/environment-ide/" + ENV_ID + "/" + VIEW_ID + "/?folder=/managed/workspace",
        "/environment-ide/" + ENV_ID + "/" + VIEW_ID + "/?path=%2F",
        "/environment-ide/" + ENV_ID + "/" + VIEW_ID + "/static/../stable-hash1234/workbench.js"
      })
  void legitimateCodeServerRoutesReachApplicationFilter(String path) throws Exception {
    HttpResponse<String> resp = send(path);
    assertEquals(
        403, resp.statusCode(), "合法 code-server 路径应到达应用层 IdeGatewayFilter 并返回 403: " + path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "//api/platform/settings/schema",
        "/environment-ide/" + ENV_ID + "/" + VIEW_ID + "//stable-hash1234/static/workbench.js",
        "/environment-ide/"
            + ENV_ID
            + "/"
            + VIEW_ID
            + "/stable-hash1234/static/out%2Fvs%2Fworkbench.js"
      })
  void ambiguousRoutesRejectedByJettyWith400(String path) throws Exception {
    HttpResponse<String> resp = send(path);
    assertEquals(400, resp.statusCode(), "歧义路径（含空段 // 或编码斜杠 %2F）应由 Jetty 接入层以 400 拒绝: " + path);
  }
}
