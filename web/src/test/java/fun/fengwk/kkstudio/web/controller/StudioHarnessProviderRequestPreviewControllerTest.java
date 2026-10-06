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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.runtime.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
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

/**
 * 请求预览 HTTP 契约：复用与既有 Thread 发送完全相同的 owner-free 请求体（path Thread + 精确 cursor +
 * 有序命令），成功响应只暴露协议请求体本身，拒绝按 400/404/409 确定性翻译。
 *
 * <p>意图：本测试锁住「预览入口不引入第二套请求形状」以及「响应不泄露 credential / endpoint / header」。真实的规划、物化与协议编码 由 {@code
 * ProviderRequestPreviewServiceIntegrationTest} 覆盖。
 */
class StudioHarnessProviderRequestPreviewControllerTest {

  private static final String OWNER_ID = "00000000-0000-0000-0000-000000000010";
  private static final String THREAD_ID = "00000000-0000-0000-0000-000000000012";
  private static final String HEAD_ENTRY_ID = "00000000-0000-0000-0000-000000000013";
  private static final String UPLOAD_ID = "00000000-0000-0000-0000-000000000014";
  private static final String BLOB_ID = "00000000-0000-0000-0000-000000000015";
  private static final String SET_MODEL_KEY = "00000000-0000-0000-0000-000000000016";
  private static final String USER_MESSAGE_KEY = "00000000-0000-0000-0000-000000000017";

  private ProviderRequestPreviewService previewService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    previewService = mock(ProviderRequestPreviewService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new StudioHarnessProviderRequestPreviewController(previewService))
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  /** 成功响应逐字段给出协议请求体与快照事实，且绝不出现 credential / endpoint / 认证 Header。 */
  @Test
  void previewReturnsEncodedBodyWithoutAnyCredentialOrEndpoint() throws Exception {
    String body = "{\"model\":\"acceptance-stub\",\"messages\":[{\"role\":\"user\"}]}";
    HarnessProviderRequestPreviewDTO dto = new HarnessProviderRequestPreviewDTO();
    dto.setKind(HarnessProviderRequestPreviewDTO.DRAFT_REQUEST_PREVIEW);
    dto.setGeneratedAt(Instant.parse("2026-01-01T00:00:00Z"));
    dto.setProviderType("OPENAI");
    dto.setModelName("acceptance-stub");
    dto.setBodyByteSize(body.getBytes(StandardCharsets.UTF_8).length);
    dto.setBodyJson(body);
    dto.setSourceHeadEntryId(HEAD_ENTRY_ID);
    dto.setNotice(HarnessProviderRequestPreviewDTO.DRAFT_NOTICE);
    when(previewService.preview(any(UUID.class), any())).thenReturn(dto);

    String response =
        mockMvc
            .perform(
                post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(batch(draftCommands())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.kind").value("DRAFT_REQUEST_PREVIEW"))
            .andExpect(jsonPath("$.data.providerType").value("OPENAI"))
            .andExpect(jsonPath("$.data.modelName").value("acceptance-stub"))
            .andExpect(jsonPath("$.data.bodyByteSize").value(dto.getBodyByteSize()))
            .andExpect(jsonPath("$.data.bodyJson").value(body))
            .andExpect(jsonPath("$.data.sourceHeadEntryId").value(HEAD_ENTRY_ID))
            .andExpect(jsonPath("$.data.generatedAt").value("2026-01-01T00:00:00Z"))
            .andExpect(
                jsonPath("$.data.notice").value(HarnessProviderRequestPreviewDTO.DRAFT_NOTICE))
            .andExpect(jsonPath("$.data.snapshotNotice").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertFalse(response.contains("secret"), "响应不得包含任何凭据");
    assertFalse(response.contains("http://"), "响应不得包含任何 endpoint");
    assertFalse(response.toLowerCase().contains("authorization"), "响应不得包含认证 Header");
  }

  /**
   * 测试意图：预览与发送共用同一请求体与同一映射——path Thread、精确 cursor、SET_* 前缀与 USER_MESSAGE 内容必须在控制器入口被精确解析；ATTACHMENT
   * 保持 wire 形态（预览绝不在此层物化或消费 upload）。
   */
  @Test
  void previewMapsCanonicalThreadCursorAndUserMessageContents() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenReturn(new HarnessProviderRequestPreviewDTO());
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isOk());

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(previewService).preview(eq(UUID.fromString(THREAD_ID)), commandCaptor.capture());

    AcceptCommandsTarget.Thread target =
        assertInstanceOf(AcceptCommandsTarget.Thread.class, commandCaptor.getValue().target());
    assertEquals(UUID.fromString(THREAD_ID), target.threadId());
    assertEquals(UUID.fromString(HEAD_ENTRY_ID), target.expectedHeadEntryId());
    assertEquals(7L, target.expectedNextCommandSequence());

    List<NewThreadCommandPayloadView> payloads =
        commandCaptor.getValue().commands().stream()
            .map(
                command ->
                    new NewThreadCommandPayloadView(command.payload(), command.requestHash()))
            .toList();
    assertEquals(
        List.of(ThreadCommandType.SET_MODEL, ThreadCommandType.USER_MESSAGE),
        payloads.stream().map(view -> view.payload().type()).toList());
    SetModelCommandPayload setModel =
        assertInstanceOf(SetModelCommandPayload.class, payloads.get(0).payload());
    assertEquals("stub", setModel.model().providerName());
    assertEquals("acceptance-stub", setModel.model().modelName());
    assertTrue(
        payloads.get(0).requestHash().matches("[0-9a-f]{64}"),
        "SET_MODEL 必须携带 canonical request hash");
    assertEquals(
        UUID.fromString(SET_MODEL_KEY),
        commandCaptor.getValue().commands().get(0).idempotencyKey());
    assertEquals(
        UUID.fromString(USER_MESSAGE_KEY),
        commandCaptor.getValue().commands().get(1).idempotencyKey());

    UserMessageCommandPayload user =
        assertInstanceOf(UserMessageCommandPayload.class, payloads.get(1).payload());
    assertEquals(new TextMessageContent("hello"), user.message().contents().get(0));
    AttachmentMessageContent attachment =
        assertInstanceOf(AttachmentMessageContent.class, user.message().contents().get(1));
    assertEquals(UUID.fromString(UPLOAD_ID), attachment.uploadId());
    assertEquals(ImageInputTier.P720, attachment.imageTier(), "缺省档位在 wire 上是 720P");
    ResourceMessageContent resource =
        assertInstanceOf(ResourceMessageContent.class, user.message().contents().get(2));
    assertEquals(UUID.fromString(BLOB_ID), resource.blobId());
    assertEquals("report.txt", resource.name());
    assertEquals(ImageInputTier.ORIGINAL, resource.imageTier());
  }

  /** 当前事实不允许精确预览（快照漂移、非空闲、queued、压缩、附件未 READY、adapter 不支持）统一是 409。 */
  @Test
  void previewTranslatesUnavailableFactsToConflict() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenThrow(
            new ProviderRequestPreviewUnavailableException(
                Reason.PREVIEW_THREAD_BUSY,
                "thread is not idle; the next step is not an input turn"));

    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("PREVIEW_THREAD_BUSY"))
        .andExpect(
            jsonPath("$.errors.detail")
                .value("thread is not idle; the next step is not an input turn"));
  }

  /** 测试意图：九类拒绝在实际 MVC 序列化后保留 reason，但内部 cause/connection 详情不得成为响应字段。 */
  @ParameterizedTest
  @EnumSource(Reason.class)
  void previewPublishesTypedReasonWithoutCauseDetails(Reason reason) throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenThrow(
            new ProviderRequestPreviewUnavailableException(
                reason,
                "safe preview detail",
                new IllegalStateException("private connection detail")));
    String response =
        mockMvc
            .perform(
                post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(batch(draftCommands())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errors.reason").value(reason.name()))
            .andExpect(jsonPath("$.errors.detail").value("safe preview detail"))
            .andExpect(jsonPath("$.errors.cause").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains("private connection detail"));
  }

  /** 缺失 Thread 与其它运行时查询一致是 404（不因为「预览」而变成 409 或 200）。 */
  @Test
  void previewTranslatesMissingThreadToNotFound() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenThrow(new HarnessRuntimeNotFoundException("thread not found"));

    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors.detail").value("thread not found"));
  }

  /** 非法请求形状是请求错误（400），并且绝不调用预览服务。 */
  @Test
  void previewRejectsMalformedRequestsBeforeTouchingTheService() throws Exception {
    // 非 canonical UUID path：与其它 thread 端点一致地 400。
    mockMvc
        .perform(
            post("/api/harness/threads/not-a-uuid/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isBadRequest());

    // CUSTOM_MESSAGE 属于内部 steering，绝不能出现在产品 HTTP 请求体里。
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(
                        """
                        [{
                          "type":"CUSTOM_MESSAGE",
                          "idempotencyKey":"%s"
                        }]
                        """
                            .formatted(USER_MESSAGE_KEY))))
        .andExpect(status().isBadRequest());

    // owner / target 已不在请求体中：多携带即未知字段，入口拒绝而不是静默忽略。
    String legacyOwnerAndTargetShape =
        """
        {
          "owner":{"type":"CHAT","chatId":"%s"},
          "target":{
            "type":"THREAD",
            "threadId":"%s",
            "expectedHeadEntryId":"%s",
            "expectedNextCommandSequence":"7"
          },
          "expectedHeadEntryId":"%s",
          "expectedNextCommandSequence":"7",
          "commands":%s
        }
        """
            .formatted(OWNER_ID, THREAD_ID, HEAD_ENTRY_ID, HEAD_ENTRY_ID, draftCommands());
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(legacyOwnerAndTargetShape))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("unknown thread command batch field: owner"));

    verify(previewService, never()).preview(any(UUID.class), any());
  }

  /** 预览服务自身的类型化拒绝（GOAL 草稿不属于预览形状）经统一翻译为 400，且不调用第二次。 */
  @Test
  void previewTranslatesServiceRejectionToBadRequest() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenThrow(
            new IllegalArgumentException(
                "provider request preview only accepts SET_* settings before the user message"));

    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(
                        """
                        [{
                          "type":"GOAL",
                          "idempotencyKey":"%s",
                          "text":null
                        }]
                        """
                            .formatted(USER_MESSAGE_KEY))))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.detail")
                .value(
                    "provider request preview only accepts SET_* settings before the user message"));

    verify(previewService)
        .preview(eq(UUID.fromString(THREAD_ID)), any(AcceptCommandsCommand.class));
  }

  /** 预览服务只在请求形状合法后被调用一次，因此它不可能被用作绕过形状校验的探测通道。 */
  @Test
  void previewCallsServiceExactlyOnceForAWellFormedBatch() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenReturn(new HarnessProviderRequestPreviewDTO());
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isOk());
    verify(previewService).preview(eq(UUID.fromString(THREAD_ID)), any());
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
          "contents":[
            {"type":"TEXT","text":"hello"},
            {"type":"ATTACHMENT","uploadId":"%s"},
            {"type":"RESOURCE","blobId":"%s","name":"report.txt","preview":"preview","imageTier":"ORIGINAL"}
          ]
        }]
        """
        .formatted(SET_MODEL_KEY, USER_MESSAGE_KEY, UPLOAD_ID, BLOB_ID);
  }

  /** 预览请求体与既有 Thread 发送完全同源：只携带 path Thread 的 cursor 与命令，不再携带 owner/target。 */
  private static String batch(String commands) {
    return """
        {
          "expectedHeadEntryId":"%s",
          "expectedNextCommandSequence":"7",
          "commands":%s
        }
        """
        .formatted(HEAD_ENTRY_ID, commands);
  }

  /** 断言辅助视图：只保留 payload 与 requestHash，避免测试重复展开 record。 */
  private record NewThreadCommandPayloadView(ThreadCommandPayload payload, String requestHash) {}
}
