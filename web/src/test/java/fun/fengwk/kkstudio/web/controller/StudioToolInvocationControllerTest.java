package fun.fengwk.kkstudio.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fun.fengwk.kkstudio.core.harness.tool.service.ToolInvocationDecisionService;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import fun.fengwk.kkstudio.web.WebTestApplication;

/**
 * Global tool permission decision HTTP：成功 JSON（大整数 id 字符串）、409 冲突与 400 边界。
 *
 * <p>Service 用 mock 隔离，不重复数据库集成。
 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
class StudioToolInvocationControllerTest {

  /** Greater than JS Number.MAX_SAFE_INTEGER so the API must keep it as a decimal string. */
  private static final long LARGE_ID = 9_007_199_254_740_993L;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ToolInvocationDecisionService decisionService;

  /** 成功路径返回 OK，且大于 JS safe integer 的 id 仍以字符串序列化。 */
  @Test
  void decidesSuccessfullyWithStringLargeIds() throws Exception {
    ToolInvocationDTO dto = new ToolInvocationDTO();
    dto.setId(Long.toString(LARGE_ID));
    dto.setThreadId(Long.toString(LARGE_ID + 1));
    dto.setStatus("QUEUED");
    dto.setPermissionDecision("ALLOW");
    when(decisionService.decide(eq(LARGE_ID), any())).thenReturn(dto);

    mockMvc
        .perform(
            post("/api/tool-invocations/{id}/decision", LARGE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"allow\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(Long.toString(LARGE_ID)))
        .andExpect(jsonPath("$.data.threadId").value(Long.toString(LARGE_ID + 1)))
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.permissionDecision").value("ALLOW"));
  }

  /** IllegalStateException 映射 409 Conflict。 */
  @Test
  void mapsIllegalStateExceptionToConflict() throws Exception {
    when(decisionService.decide(eq(LARGE_ID), any()))
        .thenThrow(new IllegalStateException("invocation is not waiting approval"));

    mockMvc
        .perform(
            post("/api/tool-invocations/{id}/decision", LARGE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"allow\"}"))
        .andExpect(status().isConflict());
  }

  /** 非法 path id / body 与 IllegalArgumentException 走当前 400 行为。 */
  @Test
  void mapsInvalidPathBodyAndIllegalArgumentToBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/tool-invocations/{id}/decision", "abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"allow\"}"))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/tool-invocations/{id}/decision", LARGE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest());

    when(decisionService.decide(eq(LARGE_ID), any()))
        .thenThrow(new IllegalArgumentException("decision must be allow or deny"));

    mockMvc
        .perform(
            post("/api/tool-invocations/{id}/decision", LARGE_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"maybe\"}"))
        .andExpect(status().isBadRequest());
  }
}
