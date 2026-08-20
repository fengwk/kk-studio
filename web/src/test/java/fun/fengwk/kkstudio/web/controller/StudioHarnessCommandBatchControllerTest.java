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

import fun.fengwk.kkstudio.core.studio.StudioCommandAcceptanceService;
import fun.fengwk.kkstudio.core.studio.StudioOwner;
import fun.fengwk.kkstudio.core.studio.StudioOwnerType;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
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
  private static final String CLIENT_COMMAND_ID = "00000000-0000-0000-0000-000000000014";

  private StudioCommandAcceptanceService acceptanceService;
  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    acceptanceService = mock(StudioCommandAcceptanceService.class);
    runtime = mock(HarnessRuntime.class);
    StudioHarnessCommandBatchController controller =
        new StudioHarnessCommandBatchController(acceptanceService, runtime);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
    when(acceptanceService.accept(any(StudioOwner.class), any(AcceptCommandsCommand.class)))
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
    // 三种 target 都必须经过同一 owner-aware service，而不是旧 thread 便利 API。
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
    verify(acceptanceService, times(3)).accept(any(StudioOwner.class), commandCaptor.capture());
    assertEquals(3, commandCaptor.getAllValues().size());
    assertEquals(
        AcceptCommandsTarget.NewSession.class,
        commandCaptor.getAllValues().get(0).target().getClass());
    assertEquals(
        AcceptCommandsTarget.Entry.class, commandCaptor.getAllValues().get(1).target().getClass());
    assertEquals(
        AcceptCommandsTarget.Thread.class, commandCaptor.getAllValues().get(2).target().getClass());

    ArgumentCaptor<StudioOwner> ownerCaptor = ArgumentCaptor.forClass(StudioOwner.class);
    verify(acceptanceService, times(3))
        .accept(ownerCaptor.capture(), any(AcceptCommandsCommand.class));
    assertEquals(StudioOwnerType.CHAT, ownerCaptor.getAllValues().get(0).type());
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
        .accept(any(StudioOwner.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void rejectsCustomMessageAndAnySystemSteeringFromProductHttpSurface() throws Exception {
    String custom =
        batch(
            """
            {
              "type":"CUSTOM_MESSAGE",
              "clientCommandId":"%s",
              "content":"system rules",
              "role":"SYSTEM"
            }
            """
                .formatted(CLIENT_COMMAND_ID));

    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(custom))
        .andExpect(status().isBadRequest());
    verify(acceptanceService, never())
        .accept(any(StudioOwner.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void enforcesFixedSetPrefixAndTrailingUserMessage() throws Exception {
    String wrongOrder =
        batchWithCommands(
            threadTarget(),
            """
            [{
              "type":"SET_MODEL",
              "clientCommandId":"00000000-0000-0000-0000-000000000021",
              "model":{"providerName":"openai","modelName":"gpt-5","variant":"default"}
            },
            {
              "type":"SET_AGENT",
              "clientCommandId":"00000000-0000-0000-0000-000000000022",
              "agentName":"default-assistant"
            },
            {
              "type":"USER_MESSAGE",
              "clientCommandId":"00000000-0000-0000-0000-000000000023",
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
        .accept(any(StudioOwner.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void preservesCommandReplayConflictReasonInUnifiedErrorEnvelope() throws Exception {
    when(acceptanceService.accept(any(StudioOwner.class), any(AcceptCommandsCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED,
                "client command id was reused"));

    mockMvc
        .perform(
            post("/api/ai/runtime/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(threadTarget())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("COMMAND_ID_REUSED"))
        .andExpect(jsonPath("$.errors.detail").value("client command id was reused"));
  }

  private static String batch(String target) {
    return batchWithCommands(
        target,
        """
        [{
          "type":"USER_MESSAGE",
          "clientCommandId":"%s",
          "contents":[{"type":"TEXT","text":"hello"}]
        }]
        """
            .formatted(CLIENT_COMMAND_ID));
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
          "environment":null,
          "agentName":"default-assistant",
          "model":{"providerName":"openai","modelName":"gpt-5","variant":"default"},
          "activeTools":[]
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
