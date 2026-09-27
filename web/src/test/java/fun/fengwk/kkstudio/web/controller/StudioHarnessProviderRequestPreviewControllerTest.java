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
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 请求预览 HTTP 契约：复用与发送完全相同的 batch 请求体，成功响应只暴露协议请求体本身，拒绝按 400/404/409 确定性翻译。
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
    dto.setKind(HarnessProviderRequestPreviewDTO.KIND);
    dto.setGeneratedAt(Instant.parse("2026-01-01T00:00:00Z"));
    dto.setProviderType("OPENAI");
    dto.setModelName("acceptance-stub");
    dto.setBodyByteSize(body.getBytes(StandardCharsets.UTF_8).length);
    dto.setBodyJson(body);
    dto.setSourceHeadEntryId(HEAD_ENTRY_ID);
    dto.setSnapshotNotice(HarnessProviderRequestPreviewDTO.SNAPSHOT_NOTICE);
    when(previewService.preview(any(UUID.class), any(OwnerRef.class), any())).thenReturn(dto);

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
                jsonPath("$.data.snapshotNotice")
                    .value(HarnessProviderRequestPreviewDTO.SNAPSHOT_NOTICE))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertFalse(response.contains("secret"), "响应不得包含任何凭据");
    assertFalse(response.contains("http://"), "响应不得包含任何 endpoint");
    assertFalse(response.toLowerCase().contains("authorization"), "响应不得包含认证 Header");
  }

  /**
   * 测试意图：预览与发送共用同一请求体与同一映射——owner、THREAD target 的 cursor、SET_* 前缀与 USER_MESSAGE 内容必须在控制器入口
   * 被精确解析；ATTACHMENT 保持 wire 形态（预览绝不在此层物化或消费 upload）。
   */
  @Test
  void previewMapsCanonicalOwnerThreadCursorAndUserMessageContents() throws Exception {
    when(previewService.preview(any(UUID.class), any(OwnerRef.class), any()))
        .thenReturn(new HarnessProviderRequestPreviewDTO());
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isOk());

    ArgumentCaptor<OwnerRef> ownerCaptor = ArgumentCaptor.forClass(OwnerRef.class);
    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(previewService)
        .preview(eq(UUID.fromString(THREAD_ID)), ownerCaptor.capture(), commandCaptor.capture());

    assertEquals(OwnerType.CHAT, ownerCaptor.getValue().type());
    assertEquals(
        UUID.fromString(OWNER_ID),
        assertInstanceOf(OwnerRef.Chat.class, ownerCaptor.getValue()).chatId());

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
    when(previewService.preview(any(UUID.class), any(OwnerRef.class), any()))
        .thenThrow(
            new ProviderRequestPreviewUnavailableException(
                "thread is not idle; the next step is not an input turn"));

    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.errors.detail")
                .value("thread is not idle; the next step is not an input turn"));
  }

  /** 缺失 Thread 与其它运行时查询一致是 404（不因为「预览」而变成 409 或 200）。 */
  @Test
  void previewTranslatesMissingThreadToNotFound() throws Exception {
    when(previewService.preview(any(UUID.class), any(OwnerRef.class), any()))
        .thenThrow(new HarnessRuntimeNotFoundException("thread not found"));

    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors.detail").value("thread not found"));
  }

  /** 越权资源与非法请求形状是请求错误（400），并且绝不调用预览服务。 */
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
                          "idempotencyKey":"%s",
                          "contents":[{"type":"TEXT","text":"system rules"}]
                        }]
                        """
                            .formatted(USER_MESSAGE_KEY))))
        .andExpect(status().isBadRequest());

    // target 形态错误（THREAD 不得携带 sessionId）同样在入口被拒绝。
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(draftCommands())
                        .replace(
                            "\"expectedHeadEntryId\":\"%s\"".formatted(HEAD_ENTRY_ID),
                            "\"sessionId\":\"%s\",\"expectedHeadEntryId\":\"%s\""
                                .formatted(HEAD_ENTRY_ID, HEAD_ENTRY_ID))))
        .andExpect(status().isBadRequest());

    // 历史 owner.id 不能被静默接受为 chatId，避免错误身份进入只读授权。
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(draftCommands())
                        .replace(
                            "\"chatId\":\"%s\"".formatted(OWNER_ID),
                            "\"id\":\"%s\"".formatted(OWNER_ID))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("unknown command owner field: id"));

    verify(previewService, never()).preview(any(UUID.class), any(OwnerRef.class), any());
  }

  /** Issue Agent Session 的输入必须经 Issue 业务工作流，预览入口不提供第二条写入通道。 */
  @Test
  void previewRejectsIssueAgentSessionOwnerAtPublicHttpBoundary() throws Exception {
    when(previewService.preview(any(UUID.class), any(OwnerRef.class), any()))
        .thenThrow(
            new IllegalArgumentException("provider request preview is limited to CHAT owners"));

    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(draftCommands())
                        .replace(
                            "\"type\":\"CHAT\",\"chatId\":\"%s\"".formatted(OWNER_ID),
                            "\"type\":\"ISSUE_AGENT\",\"issueId\":\"%s\",\"agentName\":\"executor\""
                                .formatted(OWNER_ID))))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.detail")
                .value("provider request preview is limited to CHAT owners"));
  }

  /** 预览服务只在请求形状合法后被调用一次，因此它不可能被用作绕过形状校验的探测通道。 */
  @Test
  void previewCallsServiceExactlyOnceForAWellFormedBatch() throws Exception {
    when(previewService.preview(any(UUID.class), any(OwnerRef.class), any()))
        .thenReturn(new HarnessProviderRequestPreviewDTO());
    mockMvc
        .perform(
            post("/api/harness/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isOk());
    verify(previewService).preview(eq(UUID.fromString(THREAD_ID)), any(), any());
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

  private static String batch(String commands) {
    return """
        {
          "owner":{"type":"CHAT","chatId":"%s"},
          "target":{
            "type":"THREAD",
            "threadId":"%s",
            "expectedHeadEntryId":"%s",
            "expectedNextCommandSequence":"7"
          },
          "commands":%s
        }
        """
        .formatted(OWNER_ID, THREAD_ID, HEAD_ENTRY_ID, commands);
  }

  /** 断言辅助视图：只保留 payload 与 requestHash，避免测试重复展开 record。 */
  private record NewThreadCommandPayloadView(ThreadCommandPayload payload, String requestHash) {}
}
