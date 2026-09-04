package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.UUID;

/** 唯一 owner-aware HTTP 写入口：三 target、严格字段、固定 command shape 与 accepted response。 */
class StudioHarnessCommandBatchControllerTest {

  private static final String OWNER_ID = "00000000-0000-0000-0000-000000000010";
  private static final String SESSION_ID = "00000000-0000-0000-0000-000000000011";
  private static final String THREAD_ID = "00000000-0000-0000-0000-000000000012";
  private static final String ENTRY_ID = "00000000-0000-0000-0000-000000000013";
  private static final String IDEMPOTENCY_KEY = "00000000-0000-0000-0000-000000000014";

  private HarnessCommandAcceptanceOrchestrator acceptanceService;
  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    acceptanceService = mock(HarnessCommandAcceptanceOrchestrator.class);
    runtime = mock(HarnessRuntime.class);
    StudioHarnessCommandBatchController controller =
        new StudioHarnessCommandBatchController(acceptanceService, runtime);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
    when(acceptanceService.accept(any(OwnerRef.class), any(AcceptCommandsCommand.class)))
        .thenReturn(
            new AcceptedCommands(
                HarnessRuntimeTestFixtures.session(),
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.thread(UUID.fromString(THREAD_ID)),
                List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
                false));
    when(runtime.getThreadSnapshot(any())).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());
  }

  @Test
  void acceptsNewSessionEntryAndThreadTargetsAndMapsCurrentSnapshotResponse() throws Exception {
    // 三种 target 都经过同一 owner-aware service。
    for (String target : List.of(newSessionTarget(), entryTarget(), threadTarget())) {
      mockMvc
          .perform(
              post("/api/ai/runtime/command-batches")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batch(target)))
          .andExpect(status().isAccepted())
          .andExpect(
              jsonPath("$.data.session.sessionId").value("00000000-0000-0000-0000-000000000001"))
          .andExpect(jsonPath("$.data.rootEntry.entryType").value("ROOT"))
          .andExpect(
              jsonPath("$.data.thread.threadId").value("00000000-0000-0000-0000-000000000001"))
          .andExpect(jsonPath("$.data.acceptedCommands", hasSize(1)))
          .andExpect(jsonPath("$.data.acceptedCommands[0].type").value("USER_MESSAGE"))
          .andExpect(jsonPath("$.data.replayed").value(false));
    }

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceService, times(3)).accept(any(OwnerRef.class), commandCaptor.capture());
    assertEquals(3, commandCaptor.getAllValues().size());
    assertEquals(
        AcceptCommandsTarget.NewSession.class,
        commandCaptor.getAllValues().get(0).target().getClass());
    assertEquals(
        AcceptCommandsTarget.Entry.class, commandCaptor.getAllValues().get(1).target().getClass());
    assertEquals(
        AcceptCommandsTarget.Thread.class, commandCaptor.getAllValues().get(2).target().getClass());

    ArgumentCaptor<OwnerRef> ownerCaptor = ArgumentCaptor.forClass(OwnerRef.class);
    verify(acceptanceService, times(3))
        .accept(ownerCaptor.capture(), any(AcceptCommandsCommand.class));
    assertEquals(OwnerType.CHAT, ownerCaptor.getAllValues().get(0).type());
    assertEquals(UUID.fromString(OWNER_ID), ownerCaptor.getAllValues().get(0).id());
  }

  @Test
  void rejectsForbiddenTargetFieldAndUnknownFieldBeforeCallingAcceptanceService() throws Exception {
    String forbidden =
        batch(
            """
            {
              "type":"NEW_SESSION",
              "sessionId":"%s",
              "threadId":"%s",
              "rootSettings":%s,
              "yoloEnabled":true,
              "startEntryId":"%s"
            }
            """
                .formatted(SESSION_ID, THREAD_ID, rootSettings(), ENTRY_ID));
    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(forbidden))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(
                        threadTarget()
                            .replace(
                                "\"expectedNextCommandSequence\":\"4\"",
                                "\"unknown\":true,\"expectedNextCommandSequence\":\"4\""))))
        .andExpect(status().isBadRequest());
    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void rejectsCustomMessageAndAnySystemSteeringFromProductHttpSurface() throws Exception {
    String custom =
        batch(
            """
            {
              "type":"CUSTOM_MESSAGE",
              "idempotencyKey":"%s",
              "content":"system rules",
              "role":"SYSTEM"
            }
            """
                .formatted(IDEMPOTENCY_KEY));

    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(custom))
        .andExpect(status().isBadRequest());
    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void enforcesFixedSetPrefixAndTrailingUserMessage() throws Exception {
    String wrongOrder =
        batchWithCommands(
            threadTarget(),
            """
            [{
              "type":"SET_MODEL",
              "idempotencyKey":"00000000-0000-0000-0000-000000000021",
              "model":{"providerName":"openai","modelName":"gpt-5","variant":"default"}
            },
            {
              "type":"SET_AGENT",
              "idempotencyKey":"00000000-0000-0000-0000-000000000022",
              "agentName":"default-assistant"
            },
            {
              "type":"USER_MESSAGE",
              "idempotencyKey":"00000000-0000-0000-0000-000000000023",
              "contents":[{"type":"TEXT","text":"hello"}]
            }]
            """);
    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(wrongOrder))
        .andExpect(status().isBadRequest());
    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void preservesCommandReplayConflictReasonInUnifiedErrorEnvelope() throws Exception {
    when(acceptanceService.accept(any(OwnerRef.class), any(AcceptCommandsCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.IDEMPOTENCY_KEY_REUSED,
                "client command id was reused"));

    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(threadTarget())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.errors.detail").value("client command id was reused"));
  }

  @Test
  void rejectsUnknownFieldInSetEnvironmentCommand() throws Exception {
    // 意图：验证 SET_ENVIRONMENT 命令携带未定义字段被严格拒绝且 detail 包含该字段，不调用底层服务。
    String payload =
        batchWithCommands(
            threadTarget(),
            """
            [{
              "type":"SET_ENVIRONMENT",
              "idempotencyKey":"%s",
              "unexpectedField":{"workspacePath":"."}
            }]
            """
                .formatted(IDEMPOTENCY_KEY));

    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.errors.type").value("about:blank"))
        .andExpect(jsonPath("$.errors.title").value("Bad Request"))
        .andExpect(
            jsonPath("$.errors.detail").value("unknown HTTP command field: unexpectedField"));

    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void fallsBackToGenericDetailWhenPayloadJsonIsMalformed() throws Exception {
    // 意图：验证畸形 JSON 请求体安全回退通用 detail 且不泄露 parser 细节，不调用底层服务。
    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ invalid json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.errors.type").value("about:blank"))
        .andExpect(jsonPath("$.errors.title").value("Bad Request"))
        .andExpect(jsonPath("$.errors.detail").value("Failed to read request"));

    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  private static String batch(String target) {
    return batchWithCommands(
        target,
        """
        [{
          "type":"USER_MESSAGE",
          "idempotencyKey":"%s",
          "contents":[{"type":"TEXT","text":"hello"}]
        }]
        """
            .formatted(IDEMPOTENCY_KEY));
  }

  private static String batchWithCommands(String target, String commands) {
    return """
        {
          "owner":{"type":"CHAT","id":"%s"},
          "target":%s,
          "commands":%s
        }
        """
        .formatted(OWNER_ID, target, commands);
  }

  private static String rootSettings() {
    return """
        {
          "workspacePath":null,
          "agentName":"default-assistant",
          "model":{"providerName":"openai","modelName":"gpt-5","variant":"default"}
        }
        """;
  }

  private static String newSessionTarget() {
    return """
        {
          "type":"NEW_SESSION",
          "sessionId":"%s",
          "threadId":"%s",
          "rootSettings":%s,
          "yoloEnabled":true
        }
        """
        .formatted(SESSION_ID, THREAD_ID, rootSettings());
  }

  private static String entryTarget() {
    return """
        {
          "type":"ENTRY",
          "sessionId":"%s",
          "startEntryId":"%s",
          "threadId":"%s",
          "yoloEnabled":false
        }
        """
        .formatted(SESSION_ID, ENTRY_ID, THREAD_ID);
  }

  private static String threadTarget() {
    return """
        {
          "type":"THREAD",
          "threadId":"%s",
          "expectedHeadEntryId":"%s",
          "expectedNextCommandSequence":"4"
        }
        """
        .formatted(THREAD_ID, ENTRY_ID);
  }
}
