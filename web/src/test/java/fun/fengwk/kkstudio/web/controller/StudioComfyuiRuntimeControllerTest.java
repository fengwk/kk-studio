package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.core.comfyui.ComfyuiFileDownload;
import fun.fengwk.kkstudio.core.comfyui.ComfyuiProperties;
import fun.fengwk.kkstudio.core.comfyui.ComfyuiRuntimeService;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowCancelDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowJobDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowRunRequestDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;

/**
 * {@link StudioComfyuiRuntimeController} HTTP 契约测试。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>成功路径 submit / get / cancel / file download 的状态码与响应字段；
 *   <li>默认禁用的 {@code kk-studio.comfyui.enabled=false} 测试配置下，运行期端点必须已注册并返回 503 而非 404；
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

  // -------- 成功路径 --------

  @Test
  public void shouldReturn201AndRunIdOnSubmit() throws Exception {
    when(comfyuiRuntimeService.run(eq("happy-api"), any()))
        .thenReturn(
            ComfyuiWorkflowRunDTO.builder()
                .runId("run-abc-123")
                .status("pending")
                .defaultSelector("$.files[0]")
                .build());

    mockMvc
        .perform(
            post("/api/comfyui/workflows/happy-api/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ComfyuiWorkflowRunRequestDTO())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.runId").value("run-abc-123"))
        .andExpect(jsonPath("$.data.status").value("pending"))
        .andExpect(jsonPath("$.data.defaultSelector").value("$.files[0]"));
  }

  @Test
  public void shouldReturn200AndJobOnGet() throws Exception {
    when(comfyuiRuntimeService.getJob(eq("run-1"), any()))
        .thenReturn(ComfyuiWorkflowJobDTO.builder().runId("run-1").status("completed").build());

    mockMvc
        .perform(get("/api/comfyui/runs/run-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.runId").value("run-1"))
        .andExpect(jsonPath("$.data.status").value("completed"));
  }

  @Test
  public void shouldReturn200AndCancelledFlagOnCancel() throws Exception {
    when(comfyuiRuntimeService.cancel(eq("run-1")))
        .thenReturn(ComfyuiWorkflowCancelDTO.builder().runId("run-1").cancelled(true).build());

    mockMvc
        .perform(post("/api/comfyui/runs/run-1/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.runId").value("run-1"))
        .andExpect(jsonPath("$.data.cancelled").value(true));
  }

  @Test
  public void shouldReturn200RawBytesWithContentTypeAndDispositionOnFileDownload()
      throws Exception {
    byte[] payload = new byte[] {1, 2, 3, 4, 5};
    when(comfyuiRuntimeService.downloadFile(eq("run-1"), eq("9"), eq("images"), anyInt()))
        .thenReturn(new ComfyuiFileDownload("safe.png", "image/png", payload));

    mockMvc
        .perform(get("/api/comfyui/runs/run-1/files/9/images/0"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.parseMediaType("image/png")))
        .andExpect(content().bytes(payload))
        .andExpect(header().string("Content-Disposition", Matchers.containsString("safe.png")))
        .andExpect(header().string("Content-Disposition", Matchers.containsString("attachment")));
  }

  // -------- 失败 / 错误映射 --------

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
