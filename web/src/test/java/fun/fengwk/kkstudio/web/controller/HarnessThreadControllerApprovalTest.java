package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import fun.fengwk.kkstudio.harness.api.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.UUID;

/** Thread 的 Tool approval 人工决策端点。 */
class HarnessThreadControllerApprovalTest extends HarnessThreadControllerTestSupport {

  /** 测试意图：审批身份只能来自服务端认证主体。请求体携带 actor（无论伪造与否）都是未知字段 → 400，且绝不触达交互服务。 */
  @Test
  void approvalRejectsForgedActorFieldWithoutTouchingService() throws Exception {
    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"actor\":\"admin\"}")
                .principal(() -> "alice"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(interactionService);
    verifyNoInteractions(runtime);
  }

  /**
   * 测试意图：审批请求体只接受 {decision, decisionId, reason}，未知字段拒绝与合法请求成功必须成对验证——携带 reason 的合法 请求必须成功并把 reason
   * 原样交给交互服务，而多带一个未知字段（非 actor 的任意字段同样如此）的请求必须在 DTO 的 @JsonAnySetter 处 400，detail
   * 精确回传拒绝原因，且不触达交互服务。
   */
  @Test
  void approvalRejectsUnknownNonActorFieldWhileValidPayloadStillSucceeds() throws Exception {
    when(interactionService.decideApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());

    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"DENY\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"reason\":\"denied\"}")
                .principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));

    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"DENY\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"reason\":\"denied\",\"operator\":\"admin\"}")
                .principal(() -> "alice"))
        .andExpect(status().isBadRequest());

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(interactionService, times(1)).decideApproval(captor.capture());
    assertEquals(ToolApprovalDecision.DENIED, captor.getValue().decision());
    assertEquals("denied", captor.getValue().reason());
    verifyNoInteractions(runtime);
  }

  /**
   * 测试意图：decision/decisionId 的畸形值不得被 wire 层悄悄消化或落到交互服务：非字符串 JSON token、非 ALLOW/DENY 的 decision 文本、非
   * canonical UUID 的 decisionId 都必须在触达交互服务之前 400。
   */
  @Test
  void approvalRejectsMalformedDecisionAndDecisionIdWithoutTouchingService() throws Exception {
    for (String body :
        List.of(
            "{\"decision\":42,\"decisionId\":\"" + idText(1) + "\"}",
            "{\"decision\":\"ALLOW\",\"decisionId\":1}",
            "{\"decision\":\"MAYBE\",\"decisionId\":\"" + idText(1) + "\"}",
            "{\"decision\":\"ALLOW\",\"decisionId\":\"not-a-uuid\"}",
            "{\"decision\":\"ALLOW\",\"decisionId\":null}")) {
      mockMvc
          .perform(
              put("/api/harness/threads/"
                      + idText(1)
                      + "/tool-invocations/"
                      + idText(100)
                      + "/approval")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body)
                  .principal(() -> "alice"))
          .andExpect(status().isBadRequest());
    }

    verifyNoInteractions(interactionService);
    verifyNoInteractions(runtime);
  }

  /** 意图：审批路由返回决策后的权威 Invocation，并证明 actor 来自认证主体。 */
  @Test
  void approvalRouteReturnsWaitingInvocationAndActorFromPrincipal() throws Exception {
    when(interactionService.decideApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());
    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ALLOW\",\"decisionId\":\"" + idText(1) + "\"}")
                .principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(interactionService).decideApproval(captor.capture());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
    // 审批 actor 只能来自认证主体，请求体不携带身份。
    assertEquals("alice", captor.getValue().actor());
  }

  /**
   * 意图：审批路由必须直达被请求 Thread 自己的 invocation——即使该 Thread 带执行父关系（异步 task 的子执行），也不得因为 "task join
   * 当前是否存在或是否已交付"而改变到达的 Thread/调用身份；换言之 approval 与孩子查询都不是 join 的附属状态。
   */
  @Test
  void approvalRouteOnChildThreadTargetsThatThreadInvocation() throws Exception {
    UUID childThreadId = id(7);
    UUID toolInvocationId = id(100);
    when(interactionService.decideApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());

    mockMvc
        .perform(
            put("/api/harness/threads/"
                    + idText(7)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"ALLOW\",\"decisionId\":\"" + idText(51) + "\"}")
                .principal(() -> "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(interactionService).decideApproval(captor.capture());
    assertEquals(childThreadId, captor.getValue().threadId());
    assertEquals(toolInvocationId, captor.getValue().toolInvocationId());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
    assertEquals("alice", captor.getValue().actor());
  }
}
