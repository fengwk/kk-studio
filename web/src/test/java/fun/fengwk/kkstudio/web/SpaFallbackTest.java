package fun.fengwk.kkstudio.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SPA fallback 行为测试。
 *
 * <p>覆盖 BrowserRouter 刷新回退的所有边界，确保：
 *
 * <ul>
 *   <li>Controller/API（如 {@code /healthz}）正常返回；
 *   <li>真实静态资源（如 {@code /assets/index.js}）由 {@code PathResourceResolver} 正常返回；
 *   <li>不存在的 {@code /api/**} 与 {@code /actuator/**} 返回 404 而不是 {@code index.html}；
 *   <li>不存在且带文件扩展名的资源返回 404 而不是 {@code index.html}；
 *   <li>不存在且无扩展名的前端 GET（如 {@code /chats/123}、{@code /canvas}）返回 {@code index.html}；
 *   <li>非 GET 请求不会触发 fallback。
 * </ul>
 *
 * <p>本测试不依赖 distribution profile，可在普通 {@code mvn test} 中运行；测试资源 {@code static/index.html} 与 {@code
 * static/assets/index.js} 由 {@code src/test/resources} 提供。
 */
@AutoConfigureMockMvc
public class SpaFallbackTest extends WebPostgresTestSupport {

  private static final String INDEX_BODY_FRAGMENT = "<div id=\"root\"></div>";

  @Autowired private MockMvc mockMvc;

  @Test
  public void shouldServeExistingControllerRoute() throws Exception {
    mockMvc.perform(get("/healthz")).andExpect(status().isOk());
  }

  @Test
  public void shouldReturn404ForMissingApiRoute() throws Exception {
    mockMvc.perform(get("/api/studio-chats-this-does-not-exist")).andExpect(status().isNotFound());
  }

  @Test
  public void shouldReturn404ForMissingActuatorRoute() throws Exception {
    mockMvc
        .perform(get("/actuator/studio-actuator-this-does-not-exist"))
        .andExpect(status().isNotFound());
  }

  @Test
  public void shouldReturn404ForMissingExtensionRoute() throws Exception {
    mockMvc.perform(get("/studio-missing.txt")).andExpect(status().isNotFound());
  }

  @Test
  public void shouldServeIndexHtmlForUnknownSpaRouteWithId() throws Exception {
    mockMvc
        .perform(get("/chats/123"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString(INDEX_BODY_FRAGMENT)));
  }

  @Test
  public void shouldServeIndexHtmlForUnknownRootSpaRoute() throws Exception {
    mockMvc
        .perform(get("/canvas"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString(INDEX_BODY_FRAGMENT)));
  }

  @Test
  public void shouldServeExistingStaticAssetInsteadOfIndex() throws Exception {
    mockMvc
        .perform(get("/assets/index.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("kkStudioTestAsset")));
  }

  @Test
  public void shouldNotFallbackForNonGetOnSpaRoute() throws Exception {
    mockMvc.perform(post("/chats/123")).andExpect(status().is4xxClientError());
  }
}
