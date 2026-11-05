package fun.fengwk.kkstudio.web.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.canvas.http.CanvasResourceController;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

/**
 * 真实 Spring MVC 下 Canvas Resource 直读边界的统一 {@code Result} 错误契约。
 *
 * <p>{@link CanvasResourceController} 的缺失资源和无效参数分别走领域异常和状态异常映射；该控制器由 Canvas 模块自动配置注册，本测试验证 Web
 * 组合根对它们的覆盖。
 */
@AutoConfigureMockMvc
class CanvasResourceControllerAdviceIntegrationTest extends WebPostgresTestSupport {

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
        .andExpect(jsonPath("$.code").value("not_found"))
        .andExpect(jsonPath("$.errors.resource").value("canvas"))
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
