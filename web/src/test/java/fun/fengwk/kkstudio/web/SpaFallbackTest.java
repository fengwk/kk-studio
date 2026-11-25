package fun.fengwk.kkstudio.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.platform.account.StudioBrowserSession;

/**
 * SPA fallback 行为测试。
 *
 * <p>覆盖 BrowserRouter 刷新回退的所有边界，确保：
 *
 * <ul>
 *   <li>Controller/API（如 {@code /api/platform/settings/schema}）正常返回；
 *   <li>真实静态资源（如 {@code /assets/index.js}）由 {@code PathResourceResolver} 正常返回；
 *   <li>不存在的 {@code /api/**}、{@code /actuator/**}、{@code /ws/**}、{@code /ide/**} 与 {@code
 *       /internal/**} 返回 404；
 *   <li>不存在且带文件扩展名的资源返回 404 而不是 {@code index.html}；
 *   <li>不存在且无扩展名的前端 GET（如 {@code /chats/123}、{@code /canvas}）返回 {@code index.html}；
 *   <li>非 GET 请求不会触发 fallback。
 *   <li>SPA 外壳每次回源验证，带 hash 的构建产物长期缓存且 immutable，其它静态文件与缺失资源不带缓存策略。
 * </ul>
 *
 * <p>本测试不依赖 distribution profile，可在普通 {@code mvn test} 中运行；测试资源 {@code static/index.html}、{@code
 * static/assets/index.js} 与 {@code static/plain.txt} 由 {@code src/test/resources} 提供。
 */
@AutoConfigureMockMvc
public class SpaFallbackTest extends WebPostgresTestSupport {

  private static final String INDEX_BODY_FRAGMENT = "<div id=\"root\"></div>";

  @Autowired private MockMvc mockMvc;

  @Test
  public void shouldServeExistingControllerRoute() throws Exception {
    mockMvc.perform(get("/api/platform/settings/schema")).andExpect(status().isOk());
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
  public void shouldNotFallbackForChannelAndIdePaths() throws Exception {
    // WebSocket 路径不属于 SPA；IDE 路径用管理员会话验证已认证请求仍返回 404。
    for (String path : new String[] {"/ws", "/ws/missing"}) {
      mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }
    Cookie adminCookie =
        new Cookie(
            StudioBrowserSession.COOKIE,
            adminCookieHeader().substring((StudioBrowserSession.COOKIE + "=").length()));
    for (String path : new String[] {"/ide", "/ide/missing"}) {
      mockMvc.perform(get(path).cookie(adminCookie)).andExpect(status().isNotFound());
    }
    for (String path : new String[] {"/workspace", "/ideas"}) {
      mockMvc
          .perform(get(path))
          .andExpect(status().isOk())
          .andExpect(content().string(containsString(INDEX_BODY_FRAGMENT)));
    }
  }

  /** 未映射的 {@code /internal/**} 是集群内部面，必须保持 problem+json 404，不能回退为 SPA。 */
  @Test
  public void shouldReturnJson404ForMissingInternalRoute() throws Exception {
    for (String path : new String[] {"/internal", "/internal/ide/missing"}) {
      mockMvc
          .perform(get(path))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("not_found"))
          .andExpect(jsonPath("$.status").value(404));
    }
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

  /** 带内容 hash 的构建产物可以按文件名永久缓存，重新部署不会改变同名文件的内容。 */
  @Test
  public void shouldCacheHashedBuildAssetsImmutably() throws Exception {
    mockMvc
        .perform(get("/assets/index.js"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CACHE_CONTROL,
                    allOf(
                        containsString("max-age=31536000"),
                        containsString("public"),
                        containsString("immutable"))));
  }

  /** 外壳必须每次回源验证：否则升级后浏览器可能继续加载引用已删除 hash 资源的旧 index.html。 */
  @Test
  public void shouldRevalidateTheSpaShell() throws Exception {
    for (String path : new String[] {"/index.html", "/chats/123", "/canvas"}) {
      mockMvc
          .perform(get(path))
          .andExpect(status().isOk())
          .andExpect(content().string(containsString(INDEX_BODY_FRAGMENT)))
          .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
    }
    mockMvc
        .perform(head("/index.html"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
    // 根路径由 Boot 的 welcome page 转发到 index.html，同样属于外壳。
    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("index.html"))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
  }

  /** 其它静态文件保持默认协商，缺失的资源也不能被当作可缓存结果下发。 */
  @Test
  public void shouldLeaveOtherAndMissingStaticFilesWithoutCachePolicy() throws Exception {
    mockMvc
        .perform(get("/plain.txt"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("kkStudioPlainStaticFile")))
        .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    mockMvc
        .perform(get("/assets/index-missing.js"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    mockMvc
        .perform(get("/studio-missing.txt"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
  }
}
