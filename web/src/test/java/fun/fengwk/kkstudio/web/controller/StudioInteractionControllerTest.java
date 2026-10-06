package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.platform.interaction.InteractionQueryService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionOwnerDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 统一人工交互 API {@link StudioInteractionController} 的单元测试。 */
class StudioInteractionControllerTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  private InteractionQueryService interactionQueryService;
  private InteractionService interactionService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    interactionQueryService = mock(InteractionQueryService.class);
    interactionService = mock(InteractionService.class);
    StudioInteractionController controller =
        new StudioInteractionController(interactionQueryService, interactionService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /** 测试意图：验证 GET /api/interactions 在无参数时缺省使用 limit=50 且 cursor 为 null 调用查询服务并返回 200。 */
  @Test
  void listInteractionsHappyPathWithDefaultLimit() throws Exception {
    InteractionPageDTO page = new InteractionPageDTO();
    page.setNextCursor("1000:" + idText(1));

    InteractionDTO item = new InteractionDTO();
    item.setInteractionId(idText(1));
    item.setStatus("WAITING_INPUT");
    item.setThreadId(idText(2));
    item.setSessionId(idText(3));
    item.setRootThreadId(idText(5));
    InteractionOwnerDTO owner = new InteractionOwnerDTO();
    owner.setType("CHAT");
    owner.setChatId(idText(4));
    item.setOwner(owner);
    item.setToolCallId("call-1");
    item.setToolName("ask_user");
    item.setArgumentsJson("{\"questions\":[]}");
    item.setCreateTime(Instant.parse("2026-03-01T10:00:00Z"));
    page.setItems(List.of(item));

    when(interactionQueryService.listInteractions(null, null, 50)).thenReturn(page);

    mockMvc
        .perform(get("/api/interactions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].interactionId").value(idText(1)))
        .andExpect(jsonPath("$.data.items[0].status").value("WAITING_INPUT"))
        .andExpect(jsonPath("$.data.items[0].threadId").value(idText(2)))
        .andExpect(jsonPath("$.data.items[0].sessionId").value(idText(3)))
        .andExpect(jsonPath("$.data.items[0].rootThreadId").value(idText(5)))
        .andExpect(jsonPath("$.data.items[0].owner.type").value("CHAT"))
        .andExpect(jsonPath("$.data.items[0].owner.chatId").value(idText(4)))
        .andExpect(jsonPath("$.data.items[0].toolCallId").value("call-1"))
        .andExpect(jsonPath("$.data.items[0].toolName").value("ask_user"))
        .andExpect(jsonPath("$.data.items[0].argumentsJson").value("{\"questions\":[]}"))
        .andExpect(jsonPath("$.data.nextCursor").value("1000:" + idText(1)));

    verify(interactionQueryService).listInteractions(null, null, 50);
  }

  /** 测试意图：验证 GET /api/interactions 显式传递有效 cursor 与 limit 时，参数正确透传给查询服务。 */
  @Test
  void listInteractionsWithExplicitCursorAndLimit() throws Exception {
    String cursor = "1772445600000:" + idText(5);
    InteractionPageDTO page = new InteractionPageDTO();
    page.setNextCursor(null);
    page.setItems(List.of());

    when(interactionQueryService.listInteractions(null, cursor, 20)).thenReturn(page);

    mockMvc
        .perform(get("/api/interactions").param("cursor", cursor).param("limit", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isEmpty());

    verify(interactionQueryService).listInteractions(null, cursor, 20);
  }

  /** 测试意图：验证显式 rootThreadId 以 canonical UUID 传给查询服务，服务端据此按执行根过滤。 */
  @Test
  void listInteractionsPassesCanonicalRootThreadIdToQueryService() throws Exception {
    String rootThreadId = idText(7);
    when(interactionQueryService.listInteractions(id(7), null, 50))
        .thenReturn(new InteractionPageDTO());

    mockMvc
        .perform(get("/api/interactions").param("rootThreadId", rootThreadId))
        .andExpect(status().isOk());

    verify(interactionQueryService).listInteractions(id(7), null, 50);
  }

  /** 测试意图：非 canonical 的 rootThreadId 在触达查询服务前以 400 拒绝。 */
  @Test
  void listInteractionsRejectsNonCanonicalRootThreadId() throws Exception {
    mockMvc
        .perform(get("/api/interactions").param("rootThreadId", "not-a-uuid"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(interactionQueryService);
  }

  /** 测试意图：验证 limit 的合法边界值 1 与 100 均能正常被接收与解析。 */
  @Test
  void listInteractionsAcceptsBoundaryLimits() throws Exception {
    when(interactionQueryService.listInteractions(null, null, 1))
        .thenReturn(new InteractionPageDTO());
    when(interactionQueryService.listInteractions(null, null, 100))
        .thenReturn(new InteractionPageDTO());

    mockMvc.perform(get("/api/interactions").param("limit", "1")).andExpect(status().isOk());
    verify(interactionQueryService).listInteractions(null, null, 1);

    mockMvc.perform(get("/api/interactions").param("limit", "100")).andExpect(status().isOk());
    verify(interactionQueryService).listInteractions(null, null, 100);
  }

  /** 测试意图：验证 limit 超出 [1, 100] 范围或非十进制整数时返回 400，且不调用查询服务。 */
  @Test
  void listInteractionsRejectsInvalidLimit() throws Exception {
    List<String> invalidLimits = List.of("0", "101", "-1", "-50", "abc", "1.5", "");

    for (String invalidLimit : invalidLimits) {
      mockMvc
          .perform(get("/api/interactions").param("limit", invalidLimit))
          .andExpect(status().isBadRequest());
    }

    verifyNoInteractions(interactionQueryService);
  }

  /** 测试意图：验证非法形状的 cursor 导致 InteractionQueryService 抛出 IllegalArgumentException 时被翻译为 400。 */
  @Test
  void listInteractionsTranslatesMalformedCursorToBadRequest() throws Exception {
    when(interactionQueryService.listInteractions(null, "bad-cursor", 50))
        .thenThrow(new IllegalArgumentException("cursor must be <epochMilli>:<uuid>"));

    mockMvc
        .perform(get("/api/interactions").param("cursor", "bad-cursor"))
        .andExpect(status().isBadRequest());
  }

  /**
   * 测试意图：验证 POST /api/interactions/{interactionId}/input 在有 Principal 认证上下文时，提取
   * principal.getName().strip() 作为操作者身份，并把路径与请求体各字段正确构造为 ToolInputSubmissionCommand 交付给服务，返回 200
   * 与权威回执。
   */
  @Test
  void submitToolInputHappyPathWithAuthenticatedPrincipal() throws Exception {
    UUID invocationId = id(100);
    UUID threadId = id(101);
    UUID submissionId = id(102);
    Instant acceptedAt = Instant.parse("2026-03-01T10:00:00Z");

    ToolInputAcceptance acceptance =
        new ToolInputAcceptance(
            threadId, invocationId, new ToolInputReceipt(submissionId, "alice", acceptedAt), false);
    when(interactionService.submitInput(any())).thenReturn(acceptance);

    String body =
        """
        {
          "threadId": "%s",
          "submissionId": "%s",
          "declined": false,
          "answers": [["optionA", "optionB"], ["optionC"]]
        }
        """
            .formatted(threadId, submissionId);

    mockMvc
        .perform(
            post("/api/interactions/" + invocationId + "/input")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .principal(() -> "  alice  "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(threadId.toString()))
        .andExpect(jsonPath("$.data.interactionId").value(invocationId.toString()))
        .andExpect(jsonPath("$.data.submissionId").value(submissionId.toString()))
        .andExpect(jsonPath("$.data.actor").value("alice"))
        .andExpect(jsonPath("$.data.materialized").value(false));

    ArgumentCaptor<ToolInputSubmissionCommand> captor =
        ArgumentCaptor.forClass(ToolInputSubmissionCommand.class);
    verify(interactionService).submitInput(captor.capture());
    ToolInputSubmissionCommand command = captor.getValue();
    assertEquals(threadId, command.threadId());
    assertEquals(invocationId, command.toolInvocationId());
    assertEquals(submissionId, command.submissionId());
    assertEquals("alice", command.actor());
    assertFalse(command.declined());
    assertEquals(List.of(List.of("optionA", "optionB"), List.of("optionC")), command.answers());
  }

  /**
   * 测试意图：验证 POST /api/interactions/{interactionId}/input 在无 Principal 认证上下文时，操作者身份自动降级为固定值
   * "local-user"，绝不依赖客户端请求体指定操作者身份。
   */
  @Test
  void submitToolInputHappyPathWithoutPrincipalFallsBackToLocalUser() throws Exception {
    UUID invocationId = id(100);
    UUID threadId = id(101);
    UUID submissionId = id(102);
    Instant acceptedAt = Instant.parse("2026-03-01T10:00:00Z");

    ToolInputAcceptance acceptance =
        new ToolInputAcceptance(
            threadId,
            invocationId,
            new ToolInputReceipt(submissionId, "local-user", acceptedAt),
            true);
    when(interactionService.submitInput(any())).thenReturn(acceptance);

    String body =
        """
        {
          "threadId": "%s",
          "submissionId": "%s",
          "declined": true
        }
        """
            .formatted(threadId, submissionId);

    mockMvc
        .perform(
            post("/api/interactions/" + invocationId + "/input")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(threadId.toString()))
        .andExpect(jsonPath("$.data.interactionId").value(invocationId.toString()))
        .andExpect(jsonPath("$.data.submissionId").value(submissionId.toString()))
        .andExpect(jsonPath("$.data.actor").value("local-user"))
        .andExpect(jsonPath("$.data.materialized").value(true));

    ArgumentCaptor<ToolInputSubmissionCommand> captor =
        ArgumentCaptor.forClass(ToolInputSubmissionCommand.class);
    verify(interactionService).submitInput(captor.capture());
    ToolInputSubmissionCommand command = captor.getValue();
    assertEquals(threadId, command.threadId());
    assertEquals(invocationId, command.toolInvocationId());
    assertEquals(submissionId, command.submissionId());
    assertEquals("local-user", command.actor());
    assertTrue(command.declined());
    assertEquals(List.of(), command.answers());
  }

  /**
   * 测试意图：验证各种请求体与路径校验失败（缺少 threadId、非法 UUID、缺少 submissionId、缺少 declined、未拒答却缺少 answers、明确拒答却携带
   * answers、请求体携带伪造未知字段等）均返回 400，且绝对不调用 interactionService。
   */
  @Test
  void submitToolInputValidationFailuresReturnBadRequestWithoutCallingService() throws Exception {
    String validInteractionId = idText(100);
    String validThreadId = idText(101);
    String validSubmissionId = idText(102);

    List<String> invalidPayloads =
        List.of(
            // 缺少 threadId
            """
            {
              "submissionId": "%s",
              "declined": true
            }
            """
                .formatted(validSubmissionId),
            // 非法 threadId
            """
            {
              "threadId": "not-a-uuid",
              "submissionId": "%s",
              "declined": true
            }
            """
                .formatted(validSubmissionId),
            // 缺少 submissionId
            """
            {
              "threadId": "%s",
              "declined": true
            }
            """
                .formatted(validThreadId),
            // 非法 submissionId
            """
            {
              "threadId": "%s",
              "submissionId": "invalid-uuid",
              "declined": true
            }
            """
                .formatted(validThreadId),
            // 缺少 declined (null)
            """
            {
              "threadId": "%s",
              "submissionId": "%s"
            }
            """
                .formatted(validThreadId, validSubmissionId),
            // declined=false 但缺少 answers (null)
            """
            {
              "threadId": "%s",
              "submissionId": "%s",
              "declined": false
            }
            """
                .formatted(validThreadId, validSubmissionId),
            // declined=true 却携带了 answers 数组
            """
            {
              "threadId": "%s",
              "submissionId": "%s",
              "declined": true,
              "answers": [["optionA"]]
            }
            """
                .formatted(validThreadId, validSubmissionId),
            // 客户端试图在请求体中注入未知字段（例如 actor）
            """
            {
              "threadId": "%s",
              "submissionId": "%s",
              "declined": true,
              "actor": "mallory"
            }
            """
                .formatted(validThreadId, validSubmissionId));

    for (String payload : invalidPayloads) {
      mockMvc
          .perform(
              post("/api/interactions/" + validInteractionId + "/input")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(payload))
          .andExpect(status().isBadRequest());
    }

    // 路径上的 interactionId 非法 UUID
    String validBody =
        """
        {
          "threadId": "%s",
          "submissionId": "%s",
          "declined": true
        }
        """
            .formatted(validThreadId, validSubmissionId);

    mockMvc
        .perform(
            post("/api/interactions/not-a-canonical-uuid/input")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(interactionService);
  }

  /** 测试意图：验证 interactionService 抛出类型化业务冲突 HarnessRuntimeConflictException 时转换为 409 Conflict。 */
  @Test
  void submitToolInputTranslatesConflictExceptionToHttp409() throws Exception {
    UUID invocationId = id(100);
    UUID threadId = id(101);
    UUID submissionId = id(102);

    when(interactionService.submitInput(any()))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.INPUT_SUBMISSION_NOT_APPLICABLE,
                "tool invocation is not waiting for input"));

    String body =
        """
        {
          "threadId": "%s",
          "submissionId": "%s",
          "declined": true
        }
        """
            .formatted(threadId, submissionId);

    mockMvc
        .perform(
            post("/api/interactions/" + invocationId + "/input")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  /** 测试意图：验证 interactionService 抛出 HarnessRuntimeNotFoundException 时转换为 404 Not Found。 */
  @Test
  void submitToolInputTranslatesNotFoundExceptionToHttp404() throws Exception {
    UUID invocationId = id(100);
    UUID threadId = id(101);
    UUID submissionId = id(102);

    when(interactionService.submitInput(any()))
        .thenThrow(
            new HarnessRuntimeNotFoundException("tool invocation not found: " + invocationId));

    String body =
        """
        {
          "threadId": "%s",
          "submissionId": "%s",
          "declined": true
        }
        """
            .formatted(threadId, submissionId);

    mockMvc
        .perform(
            post("/api/interactions/" + invocationId + "/input")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
  }
}
