package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.session.AttachmentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.share.runtime.HarnessProviderRequestPreviewDTO;

import java.util.List;
import java.util.UUID;

/**
 * Thread 作用域请求预览的独有契约：复用与既有 Thread 发送完全相同的 owner-free 请求体（path Thread + 精确 cursor + 有序命令），并保持 wire
 * 形态的附件/资源内容。共享的成功、拒绝与命令映射断言见 {@link ProviderRequestPreviewContractTest}。
 */
class StudioHarnessProviderRequestPreviewControllerTest extends ProviderRequestPreviewContractTest {

  private static final String THREAD_ID = "00000000-0000-0000-0000-000000000012";
  private static final String HEAD_ENTRY_ID = "00000000-0000-0000-0000-000000000013";
  private static final String UPLOAD_ID = "00000000-0000-0000-0000-000000000014";
  private static final String BLOB_ID = "00000000-0000-0000-0000-000000000015";

  @Override
  protected Object controller() {
    return new StudioHarnessProviderRequestPreviewController(previewService);
  }

  @Override
  protected ResultActions performPreview() throws Exception {
    return mockMvc.perform(
        post("/api/threads/" + THREAD_ID + "/provider-request-preview")
            .contentType(MediaType.APPLICATION_JSON)
            .content(batch(draftCommands())));
  }

  @Override
  protected void stubPreviewSuccess(HarnessProviderRequestPreviewDTO dto) {
    when(previewService.preview(any(UUID.class), any())).thenReturn(dto);
  }

  @Override
  protected void stubPreviewUnavailable(ProviderRequestPreviewUnavailableException error) {
    when(previewService.preview(any(UUID.class), any())).thenThrow(error);
  }

  @Override
  protected void stubPreviewFailure(RuntimeException error) {
    when(previewService.preview(any(UUID.class), any())).thenThrow(error);
  }

  @Override
  protected List<NewThreadCommand> captureCommands() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenReturn(new HarnessProviderRequestPreviewDTO());
    performPreview().andExpect(status().isOk());

    ArgumentCaptor<AcceptCommandsCommand> captor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(previewService).preview(eq(UUID.fromString(THREAD_ID)), captor.capture());
    return captor.getValue().commands();
  }

  @Override
  protected String sourceHeadEntryId() {
    return HEAD_ENTRY_ID;
  }

  /**
   * 测试意图：预览与发送共用同一请求体与同一映射——path Thread、精确 cursor、SET_* 前缀与 USER_MESSAGE 内容必须在控制器入口被精确解析；ATTACHMENT
   * 保持 wire 形态（预览绝不在此层物化或消费 upload）。
   */
  @Test
  void previewMapsCanonicalThreadCursorAndUserMessageContents() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenReturn(new HarnessProviderRequestPreviewDTO());
    performPreview().andExpect(status().isOk());

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(previewService).preview(eq(UUID.fromString(THREAD_ID)), commandCaptor.capture());

    AcceptCommandsTarget.Thread target =
        assertInstanceOf(AcceptCommandsTarget.Thread.class, commandCaptor.getValue().target());
    assertEquals(UUID.fromString(THREAD_ID), target.threadId());
    assertEquals(UUID.fromString(HEAD_ENTRY_ID), target.expectedHeadEntryId());
    assertEquals(7L, target.expectedNextCommandSequence());

    List<NewThreadCommand> commands = commandCaptor.getValue().commands();
    SetExecutionCommandPayload setExecution =
        assertInstanceOf(SetExecutionCommandPayload.class, commands.get(0).payload());
    assertEquals("builtin", setExecution.execution().driver());
    assertEquals(
        new ModelSelection("acceptance-stub", "default"),
        BuiltinExecution.model(setExecution.execution()));
    assertTrue(
        commands.get(0).requestHash().matches("[0-9a-f]{64}"),
        "SET_EXECUTION 必须携带 canonical request hash");
    assertEquals(UUID.fromString(SET_EXECUTION_KEY), commands.get(0).idempotencyKey());
    assertEquals(UUID.fromString(USER_MESSAGE_KEY), commands.get(1).idempotencyKey());

    UserMessageCommandPayload user =
        assertInstanceOf(UserMessageCommandPayload.class, commands.get(1).payload());
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

  /** 非法请求形状是请求错误（400），并且绝不调用预览服务。 */
  @Test
  void previewRejectsMalformedRequestsBeforeTouchingTheService() throws Exception {
    // 非 canonical UUID path：与其它 thread 端点一致地 400。
    mockMvc
        .perform(
            post("/api/threads/not-a-uuid/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(draftCommands())))
        .andExpect(status().isBadRequest());

    // CUSTOM_MESSAGE 属于内部 steering，绝不能出现在产品 HTTP 请求体里。
    mockMvc
        .perform(
            post("/api/threads/" + THREAD_ID + "/provider-request-preview")
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

    // 请求体严格拒绝未知字段。
    String unknownFieldShape =
        """
        {
          "extra":true,
          "expectedHeadEntryId":"%s",
          "expectedNextCommandSequence":"7",
          "commands":%s
        }
        """
            .formatted(HEAD_ENTRY_ID, draftCommands());
    mockMvc
        .perform(
            post("/api/threads/" + THREAD_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(unknownFieldShape))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("unknown thread command batch field: extra"));

    verify(previewService, never()).preview(any(UUID.class), any());
  }

  /** 预览服务只在请求形状合法后被调用一次，因此它不可能被用作绕过形状校验的探测通道。 */
  @Test
  void previewCallsServiceExactlyOnceForAWellFormedBatch() throws Exception {
    when(previewService.preview(any(UUID.class), any()))
        .thenReturn(new HarnessProviderRequestPreviewDTO());
    performPreview().andExpect(status().isOk());
    verify(previewService).preview(eq(UUID.fromString(THREAD_ID)), any());
  }

  private static String draftCommands() {
    return """
        [%s,
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
        .formatted(setExecutionCommand(), USER_MESSAGE_KEY, UPLOAD_ID, BLOB_ID);
  }

  /** 预览请求体与既有 Thread 发送完全同源：只携带 path Thread 的 cursor 与命令，不携带 owner/target。 */
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
}
