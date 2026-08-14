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

import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadCommandService;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.MoveHeadCommand;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.UUID;

/**
 * {@link StudioHarnessThreadController} HTTP 契约（standalone MockMvc）：命令 batch 映射与 202、 404/409/400
 * 错误映射、head/stop/approval 请求字段透传。
 */
class StudioHarnessThreadControllerTest {

  private static final ThreadCommandPayloadJsonCodec COMMAND_PAYLOADS =
      new ThreadCommandPayloadJsonCodec();

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  private HarnessRuntime runtime;
  private ChatThreadCommandService chatThreadCommandService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    chatThreadCommandService = mock(ChatThreadCommandService.class);
    StudioHarnessThreadController controller =
        new StudioHarnessThreadController(runtime, chatThreadCommandService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void getSnapshotMapsOneConsistentProjection() throws Exception {
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(get("/api/ai/runtime/threads/" + idText(1) + "/snapshot"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value("3"))
        .andExpect(jsonPath("$.data.thread.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.thread.sessionId").value(idText(1)))
        .andExpect(jsonPath("$.data.thread.headEntryId").value(idText(1)))
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
    when(chatThreadCommandService.submitCommands(any(ThreadCommandBatch.class)))
        .thenReturn(List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()));

    String body =
        """
        {
          "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
          "expectedNextCommandSequence": 4,
          "commands": [
            {"type": "USER_MESSAGE", "contents": [{"type": "TEXT", "text": "hello"}], "clientCommandId": "00000000-0000-0000-0000-000000000101"},
            {"type": "SET_AGENT", "agentName": "default-assistant", "clientCommandId": "00000000-0000-0000-0000-000000000102"},
            {"type": "SET_MODEL",
             "model": {"providerName": "openai", "modelName": "gpt-5", "variant": "default"},
             "clientCommandId": "00000000-0000-0000-0000-000000000103"},
            {"type": "SET_ACTIVE_TOOLS", "activeTools": ["web_search"], "clientCommandId": "00000000-0000-0000-0000-000000000104"},
            {"type": "SET_YOLO", "yoloEnabled": true, "clientCommandId": "00000000-0000-0000-0000-000000000105"},
            {"type": "SET_ENVIRONMENT",
             "environment": {"name": "123e4567-e89b-12d3-a456-426614174000", "workspacePath": "."},
             "clientCommandId": "00000000-0000-0000-0000-000000000106"},
            {"type": "CUSTOM_MESSAGE", "role": "SYSTEM", "content": "rules", "clientCommandId": "00000000-0000-0000-0000-000000000107"}
          ]
        }
        """;

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data[0].sequence").value("4"))
        .andExpect(jsonPath("$.data[0].type").value("USER_MESSAGE"))
        .andExpect(jsonPath("$.data[0].state").value("QUEUED"))
        .andExpect(jsonPath("$.data[0].clientCommandId").value(idText(50)));

    ArgumentCaptor<ThreadCommandBatch> captor = ArgumentCaptor.forClass(ThreadCommandBatch.class);
    verify(chatThreadCommandService).submitCommands(captor.capture());
    ThreadCommandBatch batch = captor.getValue();
    assertEquals(id(1), batch.threadId());
    assertEquals(id(3), batch.expectedHeadEntryId());
    assertEquals(4L, batch.expectedNextCommandSequence());
    assertEquals(7, batch.commands().size());
    assertEquals(ThreadCommandType.USER_MESSAGE, batch.commands().get(0).payload().type());
    assertEquals(
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\"hello\"}]}}",
        COMMAND_PAYLOADS.encode(batch.commands().get(0).payload()));
    assertEquals(ThreadCommandType.SET_ENVIRONMENT, batch.commands().get(5).payload().type());
    assertEquals(
        "{\"environment\":{\"name\":\"123e4567-e89b-12d3-a456-426614174000\",\"workspacePath\":\".\"}}",
        COMMAND_PAYLOADS.encode(batch.commands().get(5).payload()));
    assertEquals(ThreadCommandType.CUSTOM_MESSAGE, batch.commands().get(6).payload().type());
  }

  @Test
  void enqueueCommandsRejectsForbiddenFieldAsBadRequest() throws Exception {
    String body =
        """
        {
          "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
          "expectedNextCommandSequence": 4,
          "commands": [
            {"type": "USER_MESSAGE", "contents": [{"type": "TEXT", "text": "hello"}], "agentName": "default-assistant", "clientCommandId": "00000000-0000-0000-0000-000000000101"}
          ]
        }
        """;
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void enqueueCommandsRejectsUnknownCommandFieldAsBadRequest() throws Exception {
    String body =
        """
        {
          "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
          "expectedNextCommandSequence": 4,
          "commands": [
            {"type": "SET_YOLO", "yoloEnabled": true, "unexpected": true, "clientCommandId": "00000000-0000-0000-0000-000000000101"}
          ]
        }
        """;
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verify(chatThreadCommandService, never()).submitCommands(any(ThreadCommandBatch.class));
  }

  @Test
  void enqueueCommandsMapsStructuredUserContentsToCanonicalPersistentMessage() throws Exception {
    when(chatThreadCommandService.submitCommands(any(ThreadCommandBatch.class)))
        .thenReturn(List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()));
    String body =
        """
        {
          "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
          "expectedNextCommandSequence": 4,
          "commands": [{
            "type": "USER_MESSAGE",
            "clientCommandId": "00000000-0000-0000-0000-000000000201",
            "contents": [
              {"type": "TEXT", "text": "animate this"},
              {"type": "ATTACHMENT", "uploadId": "00000000-0000-0000-0000-000000000301"}
            ]
          }]
        }
        """;

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());

    ArgumentCaptor<ThreadCommandBatch> captor = ArgumentCaptor.forClass(ThreadCommandBatch.class);
    verify(chatThreadCommandService).submitCommands(captor.capture());
    assertEquals(
        "{\"message\":{\"role\":\"USER\",\"contents\":["
            + "{\"type\":\"text\",\"text\":\"animate this\"},"
            + "{\"type\":\"attachment\",\"uploadId\":\"00000000-0000-0000-0000-000000000301\"}]}}",
        COMMAND_PAYLOADS.encodeRequest(captor.getValue().commands().get(0).payload()));
  }

  @Test
  void enqueueCommandsRejectsInvalidStructuredUserMessageShapesAsBadRequest() throws Exception {
    String prefix =
        """
        {
          "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
          "expectedNextCommandSequence": 4,
          "commands": [{
            "type": "USER_MESSAGE",
            "clientCommandId": "00000000-0000-0000-0000-000000000202",
        """;
    String suffix = """
          }]
        }
        """;
    List<String> invalidBodies =
        List.of(
            prefix + "\"contents\": []" + suffix,
            prefix
                + "\"text\": \"hello\", \"contents\": [{\"type\": \"TEXT\", \"text\": \"hello\"}]"
                + suffix,
            prefix
                + "\"text\": null, \"contents\": [{\"type\": \"TEXT\", \"text\": \"hello\"}]"
                + suffix,
            prefix
                + "\"contents\": [{\"type\": \"IMAGE\", \"mediaType\": \"audio/mpeg\","
                + " \"source\": \"image-source\"}]"
                + suffix,
            prefix
                + "\"contents\": [{\"type\": \"VIDEO\", \"mediaType\": \"video/mp4\","
                + " \"source\": \" \"}]"
                + suffix,
            prefix + "\"contents\": [{\"type\": \"TOOL\", \"text\": \"hidden\"}]" + suffix,
            prefix
                + "\"contents\": [{\"type\": \"TEXT\", \"text\": \"hello\", \"unknown\": true}]"
                + suffix,
            prefix
                + "\"contents\": [{\"type\": \"TEXT\", \"text\": \"hello\","
                + " \"mediaType\": null}]"
                + suffix,
            prefix + "\"text\": \"hello\", \"textFieldPresent\": true" + suffix,
            prefix
                + "\"contents\": [{\"type\": \"TEXT\", \"text\": \"hello\","
                + " \"mediaTypeFieldPresent\": true}]"
                + suffix);

    for (String body : invalidBodies) {
      mockMvc
          .perform(
              post("/api/ai/runtime/threads/" + idText(1) + "/commands")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verify(chatThreadCommandService, never()).submitCommands(any(ThreadCommandBatch.class));
  }

  @Test
  void mapsNotFoundConflictAndBadRequestStatuses() throws Exception {
    when(runtime.getThreadSnapshot(id(1)))
        .thenThrow(new HarnessRuntimeNotFoundException("thread 1 does not exist"));
    mockMvc
        .perform(get("/api/ai/runtime/threads/" + idText(1) + "/snapshot"))
        .andExpect(status().isNotFound());

    when(chatThreadCommandService.submitCommands(any(ThreadCommandBatch.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR, "stale cursor"));
    String body =
        """
        {
          "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
          "expectedNextCommandSequence": 4,
          "commands": [{"type": "SET_YOLO", "yoloEnabled": true, "clientCommandId": "00000000-0000-0000-0000-000000000101"}]
        }
        """;
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("STALE_COMMAND_CURSOR"))
        .andExpect(jsonPath("$.errors.detail").value("stale cursor"));

    mockMvc.perform(get("/api/ai/runtime/threads/0/snapshot")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/ai/runtime/threads/not-a-number/snapshot"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void updateHeadMovesWithRevisionCasAndReturnsThread() throws Exception {
    when(runtime.moveHead(any(MoveHeadCommand.class)))
        .thenReturn(HarnessRuntimeTestFixtures.thread(id(2)));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            put("/api/ai/runtime/threads/" + idText(1) + "/head")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetEntryId\":\"" + idText(2) + "\",\"expectedRevision\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("IDLE"));

    ArgumentCaptor<MoveHeadCommand> captor = ArgumentCaptor.forClass(MoveHeadCommand.class);
    verify(runtime).moveHead(captor.capture());
    assertEquals(id(1), captor.getValue().threadId());
    assertEquals(id(2), captor.getValue().targetEntryId());
    assertEquals(3L, captor.getValue().expectedRevision());
  }

  @Test
  void stopCarriesIdempotencyKeyAndRevisionAndReturnsResultDto() throws Exception {
    when(runtime.stop(any(StopCommand.class)))
        .thenReturn(
            new StopResult(
                StopResult.Status.STOPPED, HarnessRuntimeTestFixtures.thread(id(2)), id(9), 2));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/stop")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stopRequestId\":\"" + idText(9) + "\",\"expectedRevision\":\"3\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("STOPPED"))
        .andExpect(jsonPath("$.data.stoppedTurnEndEntryId").value(idText(9)))
        .andExpect(jsonPath("$.data.cancelledCommandCount").value(2))
        .andExpect(jsonPath("$.data.thread.status").value("IDLE"));

    ArgumentCaptor<StopCommand> captor = ArgumentCaptor.forClass(StopCommand.class);
    verify(runtime).stop(captor.capture());
    assertEquals(id(1), captor.getValue().threadId());
    assertEquals(id(9), captor.getValue().stopRequestId());
    assertEquals(3L, captor.getValue().expectedRevision());
  }

  @Test
  void approvalCarriesDecisionIdActorAndReason() throws Exception {
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
                        + "\",\"actor\":\"alice\","
                        + "\"reason\":\"looks safe\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(idText(100)))
        .andExpect(jsonPath("$.data.toolCallId").value("call-1"))
        .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"));

    ArgumentCaptor<ToolApprovalCommand> captor = ArgumentCaptor.forClass(ToolApprovalCommand.class);
    verify(runtime).decideToolApproval(captor.capture());
    assertEquals(id(1), captor.getValue().threadId());
    assertEquals(id(100), captor.getValue().toolInvocationId());
    assertEquals(ToolApprovalDecision.ALLOWED, captor.getValue().decision());
    assertEquals(id(1), captor.getValue().decisionId());
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
            post("/api/ai/runtime/threads/"
                    + idText(1)
                    + "/tool-invocations/"
                    + idText(100)
                    + "/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"decision\":\"DENY\",\"decisionId\":\""
                        + idText(1)
                        + "\",\"actor\":\"alice\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void serializesSevenCommandBatchJson() throws Exception {
    when(chatThreadCommandService.submitCommands(any(ThreadCommandBatch.class)))
        .thenReturn(List.of());
    // 保证 mapper 对 7 类 discriminator 的 JSON 反序列化边界（严格字段）与 HTTP 层一致。
    String body =
        """
        {
          "expectedHeadEntryId": "00000000-0000-0000-0000-000000000003",
          "expectedNextCommandSequence": 4,
          "commands": [
            {"type": "USER_MESSAGE", "contents": [{"type": "TEXT", "text": "hi"}], "clientCommandId": "00000000-0000-0000-0000-000000000101"},
            {"type": "CUSTOM_MESSAGE", "role": "USER", "content": "custom", "clientCommandId": "00000000-0000-0000-0000-000000000102"},
            {"type": "SET_AGENT", "agentName": "a", "clientCommandId": "00000000-0000-0000-0000-000000000103"},
            {"type": "SET_MODEL", "model": {"providerName": "p", "modelName": "m", "variant": "v"}, "clientCommandId": "00000000-0000-0000-0000-000000000104"},
            {"type": "SET_ACTIVE_TOOLS", "activeTools": [], "clientCommandId": "00000000-0000-0000-0000-000000000105"},
            {"type": "SET_YOLO", "yoloEnabled": false, "clientCommandId": "00000000-0000-0000-0000-000000000106"},
            {"type": "SET_ENVIRONMENT", "environment": null, "clientCommandId": "00000000-0000-0000-0000-000000000107"}
          ]
        }
        """;
    mockMvc
        .perform(
            post("/api/ai/runtime/threads/" + idText(1) + "/commands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted());

    ArgumentCaptor<ThreadCommandBatch> captor = ArgumentCaptor.forClass(ThreadCommandBatch.class);
    verify(chatThreadCommandService).submitCommands(captor.capture());
    ThreadCommandBatch batch = captor.getValue();
    assertEquals(7, batch.commands().size());
    assertEquals(
        "{\"environment\":null}", COMMAND_PAYLOADS.encode(batch.commands().get(6).payload()));
    assertEquals(
        "{\"activeTools\":[]}", COMMAND_PAYLOADS.encode(batch.commands().get(4).payload()));
  }
}
