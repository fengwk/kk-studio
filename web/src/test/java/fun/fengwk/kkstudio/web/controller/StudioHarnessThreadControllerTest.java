package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.CancelledUserMessage;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CompactThreadResult;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.ManualCompactionAvailability;
import fun.fengwk.kkstudio.harness.runtime.SetThreadYoloCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.platform.harness.task.SystemPromptPreviewService;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.UUID;

/** Harness Thread 控制/查询 API：snapshot availability、compact、yolo、stop 与 approval。 */
class StudioHarnessThreadControllerTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  private HarnessRuntime runtime;
  private ThreadProcessor threadProcessor;
  private SystemPromptPreviewService systemPromptPreviewService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    threadProcessor = mock(ThreadProcessor.class);
    systemPromptPreviewService = mock(SystemPromptPreviewService.class);
    StudioHarnessThreadController controller =
        new StudioHarnessThreadController(runtime, threadProcessor, systemPromptPreviewService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void snapshotProjectsManualCompactionAvailabilityFromThreadProcessor() throws Exception {
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());
    when(threadProcessor.manualCompactionAvailability(id(1)))
        .thenReturn(
            ManualCompactionAvailability.disabled(
                ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM));

    mockMvc
        .perform(get("/api/ai/runtime/threads/" + idText(1) + "/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.version").value("3"))
        .andExpect(jsonPath("$.data.thread.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.manualCompaction.available").value(false))
        .andExpect(jsonPath("$.data.manualCompaction.disabledReason").value("BELOW_MINIMUM"));
  }

  @Test
  void compactCallsInjectedThreadProcessorWithVersionFenceAndMapsCommitResult() throws Exception {
    when(threadProcessor.compactThread(any(CompactThreadCommand.class)))
        .thenReturn(new CompactThreadResult(HarnessRuntimeTestFixtures.thread(id(1)), id(2), null));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.thread.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.turnStartEntryId").value(idText(2)))
        .andExpect(jsonPath("$.data.modelInvocationId").value(nullValue()));

    ArgumentCaptor<CompactThreadCommand> captor =
        ArgumentCaptor.forClass(CompactThreadCommand.class);
    verify(threadProcessor).compactThread(captor.capture());
    assertEquals(id(1), captor.getValue().threadId());
    assertEquals(3L, captor.getValue().expectedVersion());
  }

  @Test
  void compactConflictPreservesManualUnavailableReason() throws Exception {
    when(threadProcessor.compactThread(any(CompactThreadCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE,
                "manual compaction is disabled"));

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("MANUAL_COMPACTION_UNAVAILABLE"));
  }

  @Test
  void compactRejectsNonStringVersionAtTheHttpBoundary() throws Exception {
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/compact")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}"))
        .andExpect(status().isBadRequest());
    verify(threadProcessor, never()).compactThread(any(CompactThreadCommand.class));
  }

  @Test
  void yoloCarriesVersionCasAndReturnsCurrentThread() throws Exception {
    when(runtime.setThreadYolo(any(SetThreadYoloCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(id(1)));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/ai/runtime/threads/" + idText(1) + "/yolo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"3\",\"yoloEnabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.status").value("IDLE"));

    ArgumentCaptor<SetThreadYoloCommand> captor =
        ArgumentCaptor.forClass(SetThreadYoloCommand.class);
    verify(runtime).setThreadYolo(captor.capture());
    assertEquals(3L, captor.getValue().expectedVersion());
  }

  @Test
  void stopAndApprovalRoutesRemainAvailable() throws Exception {
    when(runtime.stop(any()))
        .thenReturn(
            new StopResult(
                false,
                HarnessRuntimeTestFixtures.thread(id(1)),
                id(9),
                2,
                List.of(
                    new CancelledUserMessage(
                        1L, id(50), List.of(new TextMessageContent("hello"))))));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"" + idText(9) + "\",\"expectedVersion\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STOPPED"))
        .andExpect(jsonPath("$.data.cancelledCommandCount").value(2))
        .andExpect(jsonPath("$.data.cancelledUserMessages[0].sequence").value("1"))
        .andExpect(jsonPath("$.data.cancelledUserMessages[0].idempotencyKey").value(idText(50)))
        .andExpect(
            jsonPath("$.data.cancelledUserMessages[0].messageJson")
                .value(
                    "{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}"));

    when(runtime.decideToolApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"actor\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(runtime).decideToolApproval(captor.capture());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
  }
}
