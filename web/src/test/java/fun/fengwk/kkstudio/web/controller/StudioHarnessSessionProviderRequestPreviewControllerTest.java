package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.SetExecutionCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewUnavailableException;
import fun.fengwk.kkstudio.platform.harness.builtin.query.ProviderRequestPreviewUnavailableException.Reason;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session 作用域请求预览的独有契约：本地分支草稿（Thread 尚未创建）与历史模型输出重建。
 *
 * <p>共享的成功、拒绝与命令映射断言见 {@link ProviderRequestPreviewContractTest}；这里保留历史入口的 重建声明、草稿起点与两个入口各自的请求形状。
 */
class StudioHarnessSessionProviderRequestPreviewControllerTest
    extends ProviderRequestPreviewContractTest {

  private static final String SESSION_ID = "00000000-0000-0000-0000-000000000001";
  private static final String START_ENTRY_ID = "00000000-0000-0000-0000-000000000002";
  private static final String OUTPUT_ENTRY_ID = "00000000-0000-0000-0000-000000000003";

  @Override
  protected Object controller() {
    return new StudioHarnessSessionProviderRequestPreviewController(previewService);
  }

  @Override
  protected ResultActions performPreview() throws Exception {
    return mockMvc.perform(
        post("/api/sessions/" + SESSION_ID + "/provider-request-preview")
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody()));
  }

  @Override
  protected void stubPreviewSuccess(HarnessProviderRequestPreviewDTO dto) {
    when(previewService.previewDraft(any(UUID.class), any(UUID.class), any())).thenReturn(dto);
  }

  @Override
  protected void stubPreviewUnavailable(ProviderRequestPreviewUnavailableException error) {
    when(previewService.previewDraft(any(UUID.class), any(UUID.class), any())).thenThrow(error);
  }

  @Override
  protected void stubPreviewFailure(RuntimeException error) {
    when(previewService.previewDraft(any(UUID.class), any(UUID.class), any())).thenThrow(error);
  }

  @Override
  protected List<NewThreadCommand> captureCommands() throws Exception {
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
    performPreview().andExpect(status().isOk());
    return captured.get();
  }

  @Override
  protected String sourceHeadEntryId() {
    return START_ENTRY_ID;
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

    performPreview().andExpect(status().isOk());

    verify(previewService)
        .previewDraft(eq(UUID.fromString(SESSION_ID)), eq(UUID.fromString(START_ENTRY_ID)), any());
    List<NewThreadCommand> commands = captured.get();
    assertEquals(
        List.of(ThreadCommandType.SET_EXECUTION, ThreadCommandType.USER_MESSAGE),
        commands.stream().map(command -> command.payload().type()).toList());
    SetExecutionCommandPayload setExecution =
        assertInstanceOf(SetExecutionCommandPayload.class, commands.getFirst().payload());
    assertEquals("builtin", setExecution.execution().driver());
    assertEquals(
        new ModelSelection("acceptance-stub", "default"),
        BuiltinExecution.model(setExecution.execution()));
    assertTrue(
        commands.getFirst().requestHash().matches("[0-9a-f]{64}"),
        "SET_EXECUTION 必须携带 canonical request hash");
    UserMessageCommandPayload user =
        assertInstanceOf(UserMessageCommandPayload.class, commands.get(1).payload());
    assertEquals(
        "hello",
        assertInstanceOf(TextMessageContent.class, user.message().contents().getFirst()).text());
    assertEquals(UUID.fromString(SET_EXECUTION_KEY), commands.getFirst().idempotencyKey());
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
                "/api/sessions/"
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
            post("/api/sessions/not-a-uuid/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBody()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/sessions/" + SESSION_ID + "/entries/not-a-uuid/provider-request-preview"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/sessions/" + SESSION_ID + "/provider-request-preview")
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
            post("/api/sessions/" + SESSION_ID + "/provider-request-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"startEntryId":"%s","commands":[{
                      "type":"USER_MESSAGE","idempotencyKey":"%s","contents":[{"type":"TEXT","text":"hi"}]
                    },%s]}
                    """
                        .formatted(START_ENTRY_ID, USER_MESSAGE_KEY, setExecutionCommand())))
        .andExpect(status().isBadRequest());

    verify(previewService, never()).previewDraft(any(UUID.class), any(UUID.class), any());
    verify(previewService, never()).previewHistorical(any(UUID.class), any(UUID.class));
  }

  /** 历史入口的当前事实不允许精确预览时保留稳定 reason，且 cause 详情不进入响应。 */
  @Test
  void historicalPreviewTranslatesUnavailableFactsToConflictWithoutCauseDetails() throws Exception {
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
                    "/api/sessions/"
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

  /** 服务自身的边界拒绝（历史输出不匹配该 Session）是 400，并保留安全 detail。 */
  @Test
  void historicalPreviewTranslatesBoundaryRejectionToBadRequest() throws Exception {
    when(previewService.previewHistorical(any(UUID.class), any(UUID.class)))
        .thenThrow(
            new IllegalArgumentException(
                "start entry does not belong to the session: " + OUTPUT_ENTRY_ID));
    mockMvc
        .perform(
            get(
                "/api/sessions/"
                    + SESSION_ID
                    + "/entries/"
                    + OUTPUT_ENTRY_ID
                    + "/provider-request-preview"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.detail")
                .value("start entry does not belong to the session: " + OUTPUT_ENTRY_ID));
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
        [%s,%s]
        """
        .formatted(setExecutionCommand(), textUserMessageCommand());
  }
}
