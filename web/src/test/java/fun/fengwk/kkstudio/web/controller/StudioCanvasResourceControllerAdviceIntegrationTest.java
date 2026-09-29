package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/**
 * 真实 Spring MVC 下 Canvas Resource 直读边界的统一 {@code Result} 错误契约。
 *
 * <p>{@link StudioCanvasResourceController} 直接抛 400/404 {@code ResponseStatusException}，必须与其它公开
 * Controller 一样 落到 {@code StudioResponseStatusErrorAdvice} 的同一 envelope，避免同一个 404/400 因 Controller
 * 归属不同而形状漂移。
 */
@AutoConfigureMockMvc
class StudioCanvasResourceControllerAdviceIntegrationTest extends WebPostgresTestSupport {

  private static final String CANVAS = "00000000-0000-0000-0000-0000000000c1";
  private static final String RESOURCE = "00000000-0000-0000-0000-0000000000c2";

  @Autowired private MockMvc mockMvc;

  /** 测试意图：未知 canvas 的 404 必须返回统一 Result envelope（status/errors），而不是裸 ProblemDetail。 */
  @Test
  void unknownCanvasReturnsUnifiedResultEnvelope() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/{canvasId}/resources/{resourceId}/download-url", CANVAS, RESOURCE)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.errors.type").value("about:blank"))
        .andExpect(jsonPath("$.errors.title").value("Not Found"))
        .andExpect(jsonPath("$.errors.detail").value("unknown canvas: " + CANVAS));
  }

  /** 测试意图：非 canonical UUID 的 400 也必须走同一 envelope。 */
  @Test
  void invalidCanvasIdReturnsUnifiedResultEnvelope() throws Exception {
    mockMvc
        .perform(
            post("/api/canvases/not-a-uuid/resources/{resourceId}/download-url", RESOURCE)
                .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors.type").value("about:blank"))
        .andExpect(jsonPath("$.errors.title").value("Bad Request"));
  }
}
