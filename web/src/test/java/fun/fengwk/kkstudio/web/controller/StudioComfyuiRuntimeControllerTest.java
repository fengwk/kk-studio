package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.comfyui.ComfyuiProperties;
import fun.fengwk.kkstudio.core.comfyui.ComfyuiRuntimeService;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunRequestDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@link StudioComfyuiRuntimeController} HTTP 错误契约测试。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>默认禁用的 {@code kk-studio.comfyui.enabled=false} 测试配置下，运行期端点必须已注册并返回 503 而非 404；
 *   <li>运行期禁用 → 503；
 *   <li>submit 路径上 apiName 找不到已启用卡片 → 404；
 *   <li>参数 / 选择器校验失败 → 400。
 * </ul>
 *
 * @author fengwk
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioComfyuiRuntimeControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ComfyuiProperties comfyuiProperties;

  /** 用 mock 替换真正的运行时服务，避免触发 ComfyUIClient 调用；保留真实的 enabled 默认值。 */
  @MockitoBean private ComfyuiRuntimeService comfyuiRuntimeService;

  @BeforeEach
  public void resetEnabledFlag() {
    // 不主动修改全局配置；WebTestApplication 默认 kk-studio.comfyui.enabled 缺省即 false。
    assertTrue(!comfyuiProperties.isEnabled(), "test assumes default disabled runtime");
  }

  @Test
  public void shouldRegisterEndpointAndReturn503WhenRuntimeDisabled() throws Exception {
    // 默认配置下 kk-studio.comfyui.enabled=false；端点必须已注册并返回 503 而非 404。
    when(comfyuiRuntimeService.run(eq("any-api"), any()))
        .thenThrow(
            new IllegalStateException(
                "ComfyUI runtime is disabled (kk-studio.comfyui.enabled=false)"));

    mockMvc
        .perform(
            post("/api/comfyui/workflows/any-api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ComfyuiWorkflowRunRequestDTO())))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  public void shouldMapEnabledWorkflowNotFoundTo404() throws Exception {
    // 即使运行期 bean 本身可注入，启用 lookup miss 也必须返回 404 而不是 500。
    when(comfyuiRuntimeService.run(eq("missing-api"), any()))
        .thenThrow(new IllegalArgumentException("enabled ComfyUI workflow not found: missing-api"));

    mockMvc
        .perform(
            post("/api/comfyui/workflows/missing-api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ComfyuiWorkflowRunRequestDTO())))
        .andExpect(status().isNotFound());
  }

  @Test
  public void shouldMapSelectorValidationTo400() throws Exception {
    // 选择器静态校验失败仍归 400，不与 disabled / not-found 路径混淆。
    when(comfyuiRuntimeService.getJob(eq("run-1"), eq("$..bad")))
        .thenThrow(new IllegalArgumentException("selector must not contain '..' descent operator"));

    mockMvc
        .perform(get("/api/comfyui/runs/run-1?select=$..bad"))
        .andExpect(status().isBadRequest());
  }
}
