package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewService;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.platform.harness.model.ProviderRequestPreviewUnavailableException.Reason;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session 作用域请求预览的 HTTP 契约：本地分支草稿（Thread 尚未创建）与历史模型输出重建。
 *
 * <p>测试意图：两个入口都只接受自己的边界字段，400/404/409 与其他 Harness 端点同义；历史视图必须显式声明它不是原始发送字节，且响应绝不 泄露
 * credential、endpoint 或认证 Header。
 */
class StudioHarnessSessionProviderRequestPreviewControllerTest {

  private static final String SESSION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String START_ENTRY_ID = "00000000-0000-0000-0000-000000000002";
  private static final String OUTPUT_ENTRY_ID = "00000000-0000-0000-0000-000000000003";
  private static final String SET_MODEL_KEY = "00000000-0000-0000-0000-000000000004";
  private static final String USER_MESSAGE_KEY = "00000000-0000-0000-0000-000000000005";

  private ProviderRequestPreviewService previewService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    previewService = mock(ProviderRequestPreviewService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new StudioHarnessSessionProviderRequestPreviewController(previewService))
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /** 草稿预览成功响应给出协议请求体与稳定 cursor，且不含 credential / endpoint / 认证 Header。 */
  @Test
  void draftPreviewReturnsEncodedBodyForALocalBranchDraft() throws Exception {
    String body = "{\"model\":\"acceptance-stub\",\"messages\":[{\"role\":\"user\"}]}";
    when(previewService.previewDraft(any(UUID.class), any(UUID.class), any()))
        .thenReturn(
            previewDto(
                HarnessProviderRequestPreviewDTO.DRAFT_REQUEST_PREVIEW,
                HarnessProviderRequestPreviewDTO.DRAFT_NOTICE,
                START_ENTRY_ID,
                body));

    String response =
        mockMvc
            .perform(
                post("/api/harness/sessions/" + SESSION_ID + "/provider-request-preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(draftBody()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kind").value("DRAFT_REQUEST_PREVIEW"))
            .andExpect(
                jsonPath("$.data.notice").value(HarnessProviderRequestPreviewDTO.DRAFT_NOTICE))
            .andExpect(jsonPath("$.data.providerType").value("OPENAI"))
            .andExpect(jsonPath("$.data.modelName").value("acceptance-stub"))
            .andExpect(jsonPath("$.data.bodyJson").value(body))
            .andExpect(jsonPath("$.data.sourceHeadEntryId").value(START_ENTRY_ID))
            .andExpect(jsonPath("$.data.generatedAt").value("2026-01-01T00:00:00Z"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertFalse(response.contains("secret"), "响应不得包含任何凭据");
    assertFalse(response.toLowerCase().contains("authorization"), "响应不得包含认证 Header");
    assertFalse(response.contains("http://"), "响应不得包含任何 endpoint");
  }

  /**
   * 测试意图：草稿预览与正式接受共用同一请求形状——path Session、分支起点与有序命令（含 canonical request hash）必须被精确解析； 草稿批次不携带任何
   * cursor，也不接受 threadName 之类的创建期字段。
   */
  @Test
  void draftPreviewMapsStartEntryAndStrictCommandBatch() throws Exception {
    AtomicReference<List<NewThreadCommand>> captured = new AtomicReference<>();
    when(previewService.previewDraft(any(UUID.class), any(UUID.class), any()))
        .thenAnswer(
            invocation -> {
              captured.set(invocation.getArgument(2));
              return previewDto(
                  "DRAFT_REQUEST_PREVIEW",
                  HarnessProviderRequestPreviewDTO.DRAFT_NOTICE,
                  START_ENTRY_ID,
                  "{}");
            });

    mockMvc
        .perform(
            post("/api/harness/sessions/" + SESSION_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody()))
        .andExpect(status().isOk());

    verify(previewService)
        .previewDraft(eq(UUID.fromString(SESSION_ID)), eq(UUID.fromString(START_ENTRY_ID)), any());
    List<NewThreadCommand> commands = captured.get();
    assertEquals(
        List.of(ThreadCommandType.SET_MODEL, ThreadCommandType.USER_MESSAGE),
        commands.stream().map(command -> command.payload().type()).toList());
    SetModelCommandPayload setModel =
        assertInstanceOf(SetModelCommandPayload.class, commands.getFirst().payload());
    assertEquals("acceptance-stub", setModel.model().modelName());
    assertTrue(
        commands.getFirst().requestHash().matches("[0-9a-f]{64}"),
        "SET_MODEL 必须携带 canonical request hash");
    UserMessageCommandPayload user =
        assertInstanceOf(UserMessageCommandPayload.class, commands.get(1).payload());
    assertEquals(
        "hello",
        assertInstanceOf(TextMessageContent.class, user.message().contents().getFirst()).text());
    assertEquals(UUID.fromString(SET_MODEL_KEY), commands.getFirst().idempotencyKey());
    assertEquals(UUID.fromString(USER_MESSAGE_KEY), commands.get(1).idempotencyKey());
  }

  /** 历史预览成功响应必须声明它不是原始发送字节，并以输出的请求前缀 parent 作为 source head。 */
  @Test
  void historicalPreviewDeclaresReconstructionInsteadOfOriginalBytes() throws Exception {
    String body = "{\"model\":\"acceptance-stub\"}";
    when(previewService.previewHistorical(any(UUID.class), any(UUID.class)))
        .thenReturn(
            previewDto(
                HarnessProviderRequestPreviewDTO.HISTORICAL_REQUEST_PREVIEW,
                HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE,
                START_ENTRY_ID,
                body));

    mockMvc
        .perform(
            get(
                "/api/harness/sessions/"
                    + SESSION_ID
                    + "/entries/"
                    + OUTPUT_ENTRY_ID
                    + "/provider-request-preview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.kind").value("HISTORICAL_REQUEST_PREVIEW"))
        .andExpect(
            jsonPath("$.data.notice").value(HarnessProviderRequestPreviewDTO.HISTORICAL_NOTICE))
        .andExpect(jsonPath("$.data.sourceHeadEntryId").value(START_ENTRY_ID))
        .andExpect(jsonPath("$.data.bodyJson").value(body));

    verify(previewService)
        .previewHistorical(eq(UUID.fromString(SESSION_ID)), eq(UUID.fromString(OUTPUT_ENTRY_ID)));
  }

  /** 请求形状错误（非 canonical UUID、非法 batch、越界草稿字段）一律 400，且绝不调用预览服务。 */
  @Test
  void rejectsMalformedRequestsBeforeTouchingTheService() throws Exception {
    mockMvc
        .perform(
            post("/api/harness/sessions/not-a-uuid/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get(
                "/api/harness/sessions/"
                    + SESSION_ID
                    + "/entries/not-a-uuid/provider-request-preview"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/harness/sessions/" + SESSION_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"startEntryId":"%s","threadName":"hidden","commands":%s}
                    """
                        .formatted(START_ENTRY_ID, draftCommands())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("unknown draft preview field: threadName"));
    // 命令顺序与终止输入必须与正式接受一致：SET_* 前缀 + 恰好一条末尾 USER_MESSAGE。
    mockMvc
        .perform(
            post("/api/harness/sessions/" + SESSION_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"startEntryId":"%s","commands":[{
                      "type":"USER_MESSAGE","idempotencyKey":"%s","contents":[{"type":"TEXT","text":"hi"}]
                    },{
                      "type":"SET_MODEL","idempotencyKey":"%s",
                      "model":{"providerName":"stub","modelName":"acceptance-stub","variant":"default"}
                    }]}
                    """
                        .formatted(START_ENTRY_ID, USER_MESSAGE_KEY, SET_MODEL_KEY)))
        .andExpect(status().isBadRequest());

    verify(previewService, never()).previewDraft(any(UUID.class), any(UUID.class), any());
    verify(previewService, never()).previewHistorical(any(UUID.class), any(UUID.class));
  }

  /** 当前事实不允许精确预览（规划拒绝、附件未就绪、压缩调用不可重建、adapter 不支持）统一 409 并保留稳定 reason。 */
  @Test
  void translatesUnavailableFactsToConflict() throws Exception {
    when(previewService.previewDraft(any(UUID.class), any(UUID.class), any()))
        .thenThrow(
            new ProviderRequestPreviewUnavailableException(
                Reason.PREVIEW_ATTACHMENT_NOT_READY, "attachment upload is not READY for preview"));
    mockMvc
        .perform(
            post("/api/harness/sessions/" + SESSION_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("PREVIEW_ATTACHMENT_NOT_READY"));

    when(previewService.previewHistorical(any(UUID.class), any(UUID.class)))
        .thenThrow(
            new ProviderRequestPreviewUnavailableException(
                Reason.PREVIEW_UNSUPPORTED,
                "compaction model calls are not reconstructible through the live request path",
                new IllegalStateException("private planning detail")));
    String response =
        mockMvc
            .perform(
                get(
                    "/api/harness/sessions/"
                        + SESSION_ID
                        + "/entries/"
                        + OUTPUT_ENTRY_ID
                        + "/provider-request-preview"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errors.reason").value("PREVIEW_UNSUPPORTED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains("private planning detail"), "cause 详情不得进入响应");
  }

  /** 缺失 Session 与其它查询一致是 404；服务自身的边界拒绝（起点不合法）是 400。 */
  @Test
  void translatesMissingSessionToNotFoundAndBoundaryRejectionToBadRequest() throws Exception {
    when(previewService.previewDraft(any(UUID.class), any(UUID.class), any()))
        .thenThrow(new HarnessRuntimeNotFoundException("session is missing"));
    mockMvc
        .perform(
            post("/api/harness/sessions/" + SESSION_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors.detail").value("session is missing"));

    when(previewService.previewHistorical(any(UUID.class), any(UUID.class)))
        .thenThrow(
            new IllegalArgumentException(
                "start entry does not belong to the session: " + OUTPUT_ENTRY_ID));
    mockMvc
        .perform(
            get(
                "/api/harness/sessions/"
                    + SESSION_ID
                    + "/entries/"
                    + OUTPUT_ENTRY_ID
                    + "/provider-request-preview"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.detail")
                .value("start entry does not belong to the session: " + OUTPUT_ENTRY_ID));
  }

  private static HarnessProviderRequestPreviewDTO previewDto(
      String kind, String notice, String sourceHeadEntryId, String body) {
    HarnessProviderRequestPreviewDTO dto = new HarnessProviderRequestPreviewDTO();
    dto.setKind(kind);
    dto.setNotice(notice);
    dto.setGeneratedAt(Instant.parse("2026-01-01T00:00:00Z"));
    dto.setProviderType("OPENAI");
    dto.setModelName("acceptance-stub");
    dto.setBodyByteSize(body.getBytes(StandardCharsets.UTF_8).length);
    dto.setBodyJson(body);
    dto.setSourceHeadEntryId(sourceHeadEntryId);
    return dto;
  }

  /** 本地分支草稿请求体：只有分支起点与有序命令，没有 cursor、owner、target 或创建期字段。 */
  private static String draftBody() {
    return """
        {"startEntryId":"%s","commands":%s}
        """
        .formatted(START_ENTRY_ID, draftCommands());
  }

  private static String draftCommands() {
    return """
        [{
          "type":"SET_MODEL",
          "idempotencyKey":"%s",
          "model":{"providerName":"stub","modelName":"acceptance-stub","variant":"default"}
        },
        {
          "type":"USER_MESSAGE",
          "idempotencyKey":"%s",
          "contents":[{"type":"TEXT","text":"hello"}]
        }]
        """
        .formatted(SET_MODEL_KEY, USER_MESSAGE_KEY);
  }
}
