package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.MoveHeadCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * {@link StudioHarnessThreadController} HTTP 契约（standalone MockMvc）：命令 batch 映射与 202、 404/409/400
 * 错误映射、head/stop/approval 请求字段透传。
 */
class StudioHarnessThreadControllerTest {

  private static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS =
      new ThreadCommandPayloadJsonCodec();

  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    RealtimeEventTail tail = mock(RealtimeEventTail.class);
    ThreadRevisionSseHub hub = mock(ThreadRevisionSseHub.class);
    Executor executor = Runnable::run;
    StudioHarnessThreadController controller =
        new StudioHarnessThreadController(runtime, tail, hub, executor);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void getSnapshotMapsOneConsistentProjection() throws Exception {
    when(runtime.getThreadSnapshot(1L)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(get("/api/ai/runtime/threads/1/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value("3"))
        .andExpect(jsonPath("$.data.thread.threadId").value("1"))
        .andExpect(jsonPath("$.data.thread.sessionId").value("1"))
        .andExpect(jsonPath("$.data.thread.headEntryId").value("1"))
        .andExpect(jsonPath("$.data.thread.status").value("IDLE"))
        .andExpect(jsonPath("$.data.thread.processing").value(false))
        .andExpect(jsonPath("$.data.thread.branchSettings.agentName").value("default-assistant"))
        .andExpect(jsonPath("$.data.entries.length()").value(1))
        .andExpect(jsonPath("$.data.queuedCommands.length()").value(0))
        .andExpect(jsonPath("$.data.modelInvocation").value(nullValue()))
        .andExpect(jsonPath("$.data.toolInvocations.length()").value(0));
  }

  @Test
  void enqueueCommandsMapsOneBatchAndReturnsAccepted() throws Exception {
    when(runtime.enqueueCommands(any(ThreadCommandBatch.class)))
        .thenReturn(List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()));

    String body =
        """
        {
          "expectedHeadEntryId": "3",
          "expectedNextCommandSequence": 4,
          "commands": [
            {"type": "USER_MESSAGE", "content": "hello", "clientCommandId": "c-1"},
            {"type": "SET_AGENT", "agentName": "default-assistant", "clientCommandId": "c-2"},
            {"type": "SET_MODEL",
             "model": {"providerName": "openai", "modelName": "gpt-5", "variant": "default"},
             "clientCommandId": "c-3"},
            {"type": "SET_THINKING_LEVEL", "thinkingLevel": "high", "clientCommandId": "c-4"},
            {"type": "SET_ACTIVE_TOOLS", "activeTools": ["web_search"], "clientCommandId": "c-5"},
            {"type": "SET_YOLO", "yoloEnabled": true, "clientCommandId": "c-6"},
            {"type": "SET_ENVIRONMENT",
             "environmentId": "123e4567-e89b-12d3-a456-426614174000", "clientCommandId": "c-7"},
            {"type": "CUSTOM_MESSAGE", "role": "SYSTEM", "content": "rules", "clientCommandId": "c-8"}
          ]
        }
        """;

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/1/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data[0].commandId").value("50"))
        .andExpect(jsonPath("$.data[0].type").value("USER_MESSAGE"))
        .andExpect(jsonPath("$.data[0].state").value("QUEUED"))
        .andExpect(jsonPath("$.data[0].clientCommandId").value("client-1"));

    ArgumentCaptor<ThreadCommandBatch> captor = ArgumentCaptor.forClass(ThreadCommandBatch.class);
    verify(runtime).enqueueCommands(captor.capture());
    ThreadCommandBatch batch = captor.getValue();
    assertEquals(1L, batch.threadId());
    assertEquals(3L, batch.expectedHeadEntryId());
    assertEquals(4L, batch.expectedNextCommandSequence());
    assertEquals(8, batch.commands().size());
    assertEquals(ThreadCommandType.USER_MESSAGE, batch.commands().get(0).payload().type());
    assertEquals(
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}}",
        COMMAND_PAYLOADS.encode(batch.commands().get(0).payload()));
    assertEquals(ThreadCommandType.SET_ENVIRONMENT, batch.commands().get(6).payload().type());
    assertEquals(
        "{\"environmentId\":\"123e4567-e89b-12d3-a456-426614174000\"}",
        COMMAND_PAYLOADS.encode(batch.commands().get(6).payload()));
    assertEquals(ThreadCommandType.CUSTOM_MESSAGE, batch.commands().get(7).payload().type());
  }

  @Test
  void enqueueCommandsRejectsForbiddenFieldAsBadRequest() throws Exception {
    String body =
        """
        {
          "expectedHeadEntryId": "3",
          "expectedNextCommandSequence": 4,
          "commands": [
            {"type": "USER_MESSAGE", "content": "hello", "agentName": "default-assistant", "clientCommandId": "c-1"}
          ]
        }
        """;
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/1/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void mapsNotFoundConflictAndBadRequestStatuses() throws Exception {
    when(runtime.getThreadSnapshot(1L))
        .thenThrow(new HarnessRuntimeNotFoundException("thread 1 does not exist"));
    mockMvc.perform(get("/api/ai/runtime/threads/1/snapshot")).andExpect(status().isNotFound());

    when(runtime.enqueueCommands(any(ThreadCommandBatch.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "stale cursor"));
    String body =
        """
        {
          "expectedHeadEntryId": "3",
          "expectedNextCommandSequence": 4,
          "commands": [{"type": "SET_YOLO", "yoloEnabled": true, "clientCommandId": "c-1"}]
        }
        """;
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/1/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());

    mockMvc.perform(get("/api/ai/runtime/threads/0/snapshot")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/ai/runtime/threads/not-a-number/snapshot"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateHeadMovesWithRevisionCasAndReturnsThread() throws Exception {
    when(runtime.moveHead(any(MoveHeadCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(2));
    when(runtime.getThreadSnapshot(1L)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/ai/runtime/threads/1/head")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetEntryId\":\"2\",\"expectedRevision\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IDLE"));

    ArgumentCaptor<MoveHeadCommand> captor = ArgumentCaptor.forClass(MoveHeadCommand.class);
    verify(runtime).moveHead(captor.capture());
    assertEquals(1L, captor.getValue().threadId());
    assertEquals(2L, captor.getValue().targetEntryId());
    assertEquals(3L, captor.getValue().expectedRevision());
  }

  @Test
  void stopCarriesIdempotencyKeyAndRevisionAndReturnsResultDto() throws Exception {
    when(runtime.stop(any(StopCommand.class)))
        .thenReturn(
            new StopResult(StopResult.Status.STOPPED, HarnessRuntimeTestFixtures.thread(2), 9L, 2));
    when(runtime.getThreadSnapshot(1L)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/1/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"stop-1\",\"expectedRevision\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STOPPED"))
        .andExpect(jsonPath("$.data.stoppedTurnEndEntryId").value("9"))
        .andExpect(jsonPath("$.data.cancelledCommandCount").value(2))
        .andExpect(jsonPath("$.data.thread.status").value("IDLE"));

    ArgumentCaptor<StopCommand> captor = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime).stop(captor.capture());
    assertEquals(1L, captor.getValue().threadId());
    assertEquals("stop-1", captor.getValue().stopRequestId());
    assertEquals(3L, captor.getValue().expectedRevision());
  }

  @Test
  void approvalCarriesDecisionIdActorAndReason() throws Exception {
    when(runtime.decideToolApproval(any(ToolApprovalCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.waitingApprovalTool());

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/1/tool-invocations/100/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"ALLOW\",\"decisionId\":\"decision-1\",\"actor\":\"alice\","
                        + "\"reason\":\"looks safe\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value("100"))
        .andExpect(jsonPath("$.data.toolCallId").value("call-1"))
        .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(runtime).decideToolApproval(captor.capture());
    assertEquals(1L, captor.getValue().threadId());
    assertEquals(100L, captor.getValue().toolInvocationId());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
    assertEquals("decision-1", captor.getValue().decisionId());
    assertEquals("alice", captor.getValue().actor());
    assertEquals("looks safe", captor.getValue().reason());
  }

  @Test
  void approvalConflictMapsToConflict() throws Exception {
    when(runtime.decideToolApproval(any(ToolApprovalCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.APPROVAL_DECISION_MISMATCH, "mismatch"));
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/1/tool-invocations/100/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"DENY\",\"decisionId\":\"decision-1\",\"actor\":\"alice\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void serializesEightCommandBatchJson() throws Exception {
    when(runtime.enqueueCommands(any(ThreadCommandBatch.class))).thenReturn(List.of());
    // 保证 mapper 对 8 类 discriminator 的 JSON 反序列化边界（严格字段）与 HTTP 层一致。
    String body =
        """
        {
          "expectedHeadEntryId": "3",
          "expectedNextCommandSequence": 4,
          "commands": [
            {"type": "USER_MESSAGE", "content": "hi", "clientCommandId": "c-1"},
            {"type": "CUSTOM_MESSAGE", "role": "USER", "content": "custom", "clientCommandId": "c-2"},
            {"type": "SET_AGENT", "agentName": "a", "clientCommandId": "c-3"},
            {"type": "SET_MODEL", "model": {"providerName": "p", "modelName": "m", "variant": "v"}, "clientCommandId": "c-4"},
            {"type": "SET_THINKING_LEVEL", "thinkingLevel": "low", "clientCommandId": "c-5"},
            {"type": "SET_ACTIVE_TOOLS", "activeTools": [], "clientCommandId": "c-6"},
            {"type": "SET_YOLO", "yoloEnabled": false, "clientCommandId": "c-7"},
            {"type": "SET_ENVIRONMENT", "environmentId": null, "clientCommandId": "c-8"}
          ]
        }
        """;
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/1/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());

    ArgumentCaptor<ThreadCommandBatch> captor = ArgumentCaptor.forClass(ThreadCommandBatch.class);
    verify(runtime).enqueueCommands(captor.capture());
    ThreadCommandBatch batch = captor.getValue();
    assertEquals(8, batch.commands().size());
    assertEquals(
        "{\"environmentId\":null}", COMMAND_PAYLOADS.encode(batch.commands().get(7).payload()));
    assertEquals(
        "{\"activeTools\":[]}", COMMAND_PAYLOADS.encode(batch.commands().get(5).payload()));
  }

  @Test
  void snapshotNotFoundMapsTo404ForSseGuardPath() throws Exception {
    when(runtime.getThreadSnapshot(anyLong()))
        .thenThrow(new HarnessRuntimeNotFoundException("thread 1 does not exist"));
    mockMvc
        .perform(get("/api/ai/runtime/threads/1/events/stream"))
        .andExpect(status().isNotFound());
  }
}
