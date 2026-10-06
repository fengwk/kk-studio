package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.OwnerType;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.UUID;

/**
 * 唯一 owner-aware 创建型 HTTP 写入口：只承载 NEW_SESSION / NEW_THREAD、严格字段、固定 command shape 与 accepted
 * response；既有 Thread 的继续写入走 {@link StudioHarnessThreadCommandBatchController}。
 */
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
  void acceptsNewSessionAndNewThreadCreationTargetsAndMapsCurrentSnapshotResponse()
      throws Exception {
    // 两个创建型 target 都经过同一 owner-aware service。
    for (String target : List.of(newSessionTarget(), newThreadTarget())) {
      mockMvc
          .perform(
              post("/api/harness/command-batches")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batch(target)))
          .andExpect(status().isAccepted())
          .andExpect(
              jsonPath("$.data.session.sessionId").value("00000000-0000-0000-0000-000000000001"))
          .andExpect(jsonPath("$.data.session.name").value("session"))
          .andExpect(jsonPath("$.data.rootEntry.entryType").value("ROOT"))
          .andExpect(
              jsonPath("$.data.thread.threadId").value("00000000-0000-0000-0000-000000000001"))
          .andExpect(jsonPath("$.data.thread.name").value("thread"))
          .andExpect(jsonPath("$.data.acceptedCommands", hasSize(1)))
          .andExpect(jsonPath("$.data.acceptedCommands[0].type").value("USER_MESSAGE"))
          .andExpect(jsonPath("$.data.replayed").value(false));
    }

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceService, times(2)).accept(any(OwnerRef.class), commandCaptor.capture());
    assertEquals(2, commandCaptor.getAllValues().size());
    assertEquals(
        AcceptCommandsTarget.NewRootSession.class,
        commandCaptor.getAllValues().get(0).target().getClass());
    assertEquals(
        AcceptCommandsTarget.NewThread.class,
        commandCaptor.getAllValues().get(1).target().getClass());

    ArgumentCaptor<OwnerRef> ownerCaptor = ArgumentCaptor.forClass(OwnerRef.class);
    verify(acceptanceService, times(2))
        .accept(ownerCaptor.capture(), any(AcceptCommandsCommand.class));
    assertEquals(OwnerType.CHAT, ownerCaptor.getAllValues().get(0).type());
    assertInstanceOf(OwnerRef.Chat.class, ownerCaptor.getAllValues().get(0));
    assertEquals(
        UUID.fromString(OWNER_ID), ((OwnerRef.Chat) ownerCaptor.getAllValues().get(0)).chatId());
  }

  /**
   * 测试意图：THREAD target 与它的 cursor 字段已从创建型 union 删除；携带它们（无论是否补齐字段）都在入口 400，且不触达 service 与 runtime。
   */
  @Test
  void rejectsDeletedThreadTargetAndCursorFieldsAtTheCreationRoute() throws Exception {
    String bareThreadTarget =
        batch(
            """
            {
              "type":"THREAD",
              "threadId":"%s"
            }
            """
                .formatted(THREAD_ID));
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bareThreadTarget))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("unknown target type: THREAD"));

    // 历史 THREAD target 的 cursor 字段不再是 union 成员，多携带即未知字段。
    String legacyThreadTarget =
        batch(
            """
            {
              "type":"THREAD",
              "threadId":"%s",
              "expectedHeadEntryId":"%s",
              "expectedNextCommandSequence":"4"
            }
            """
                .formatted(THREAD_ID, ENTRY_ID));
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(legacyThreadTarget))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.detail").value("unknown command target field: expectedHeadEntryId"));

    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
    verifyNoInteractions(runtime);
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
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(forbidden))
        .andExpect(status().isBadRequest());

    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(
                        newThreadTarget()
                            .replace(
                                "\"yoloEnabled\":false",
                                "\"yoloEnabled\":false,\"unknown\":true"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("unknown command target field: unknown"));
    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  /** 意图：NEW_THREAD（wire 新 target）必须拒绝携带 NEW_SESSION 专属的 rootSettings，且 400 detail 不回显字段值。 */
  @Test
  void newThreadTargetRejectsNewSessionOnlyFieldsAndNeverEchoesValues() throws Exception {
    String forbidden =
        batch(
            """
            {
              "type":"NEW_THREAD",
              "sessionId":"%s",
              "startEntryId":"%s",
              "threadId":"%s",
              "rootSettings":%s,
              "yoloEnabled":false
            }
            """
                .formatted(SESSION_ID, ENTRY_ID, THREAD_ID, rootSettings()));
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(forbidden))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.detail")
                .value("field target.rootSettings is forbidden for target type NEW_THREAD"));

    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(
                        newThreadTarget()
                            .replace(
                                "\"yoloEnabled\":false",
                                "\"yoloEnabled\":false,\"sessionName\":\"x\""))))
        .andExpect(status().isBadRequest());
    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  @Test
  void rejectsInternalOnlyCommandTypesFromProductHttpSurface() throws Exception {
    // CUSTOM_MESSAGE / NOTIFICATION / SET_CONTRIBUTOR_STATE 都是内部 steering：产品 HTTP 请求体携带即 400。
    for (String type : List.of("CUSTOM_MESSAGE", "NOTIFICATION", "SET_CONTRIBUTOR_STATE")) {
      String internalOnly =
          batchWithCommands(
              newThreadTarget(),
              """
              [{
                "type":"%s",
                "idempotencyKey":"%s"
              }]
              """
                  .formatted(type, IDEMPOTENCY_KEY));
      mockMvc
          .perform(
              post("/api/harness/command-batches")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(internalOnly))
          .andExpect(status().isBadRequest());
    }

    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
  }

  /**
   * 测试意图：typed GOAL 是合法的终止 user-like 命令——显式 null 表示清除、文本表示设置；缺 text、与 USER_MESSAGE 共存或非末位都返回 400
   * 且不调用 acceptance service。
   */
  @Test
  void acceptsTypedGoalAsTerminalUserLikeCommandAndRejectsMalformedShapes() throws Exception {
    String withGoal =
        batchWithCommands(
            newThreadTarget(),
            """
            [{
              "type":"SET_AGENT",
              "idempotencyKey":"00000000-0000-0000-0000-000000000031",
              "agentName":"default-assistant"
            },
            {
              "type":"GOAL",
              "idempotencyKey":"00000000-0000-0000-0000-000000000032",
              "text":"ship the release"
            }]
            """);
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(withGoal))
        .andExpect(status().isAccepted());

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceService).accept(any(OwnerRef.class), commandCaptor.capture());
    List<ThreadCommandPayload> payloads =
        commandCaptor.getValue().commands().stream().map(command -> command.payload()).toList();
    assertEquals(
        List.of(ThreadCommandType.SET_AGENT, ThreadCommandType.GOAL),
        payloads.stream().map(ThreadCommandPayload::type).toList());
    assertEquals(new GoalCommandPayload("ship the release"), payloads.get(1));

    // 显式 null 是「清除 Goal」，同样合法。
    String cleared =
        batchWithCommands(
            newThreadTarget(),
            """
            [{
              "type":"GOAL",
              "idempotencyKey":"00000000-0000-0000-0000-000000000033",
              "text":null
            }]
            """);
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cleared))
        .andExpect(status().isAccepted());

    // 缺 text 的 GOAL 与「携带 text 的 USER_MESSAGE」都必须 400。
    String missingText =
        batchWithCommands(
            newThreadTarget(),
            """
            [{
              "type":"GOAL",
              "idempotencyKey":"00000000-0000-0000-0000-000000000034"
            }]
            """);
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingText))
        .andExpect(status().isBadRequest());
    String userWithText =
        batchWithCommands(
            newThreadTarget(),
            """
            [{
              "type":"USER_MESSAGE",
              "idempotencyKey":"00000000-0000-0000-0000-000000000035",
              "text":"ship",
              "contents":[{"type":"TEXT","text":"hello"}]
            }]
            """);
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(userWithText))
        .andExpect(status().isBadRequest());

    // GOAL 与 USER_MESSAGE 不能共存：恰有一条 user-like 终止命令。
    String bothUserLike =
        batchWithCommands(
            newThreadTarget(),
            """
            [{
              "type":"GOAL",
              "idempotencyKey":"00000000-0000-0000-0000-000000000036",
              "text":"ship"
            },
            {
              "type":"USER_MESSAGE",
              "idempotencyKey":"00000000-0000-0000-0000-000000000037",
              "contents":[{"type":"TEXT","text":"hello"}]
            }]
            """);
    mockMvc
        .perform(
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bothUserLike))
        .andExpect(status().isBadRequest());
  }

  @Test
  void enforcesFixedSetPrefixAndTrailingUserMessage() throws Exception {
    String wrongOrder =
        batchWithCommands(
            newThreadTarget(),
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
            post("/api/harness/command-batches")
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
            post("/api/harness/command-batches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(newThreadTarget())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.errors.detail").value("client command id was reused"));
  }

  @Test
  void rejectsUnknownFieldInSetEnvironmentCommand() throws Exception {
    // 意图：验证 SET_ENVIRONMENT 命令携带未定义字段被严格拒绝且 detail 包含该字段，不调用底层服务。
    String payload =
        batchWithCommands(
            newThreadTarget(),
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
            post("/api/harness/command-batches")
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
            post("/api/harness/command-batches")
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

  @Test
  void rejectsIssueAgentCommandsAtPublicHttpBoundary() throws Exception {
    // 测试意图：Issue Agent 的用户输入和分叉必须经 Issue 业务工作流，不能直接以 owner 伪造公共命令批，返回 409 Conflict。
    for (String target : List.of(newSessionTarget(), newThreadTarget())) {
      String request =
          batchWithCommands(
                  target,
                  """
                  [{
                    "type":"USER_MESSAGE",
                    "idempotencyKey":"%s",
                    "contents":[{"type":"TEXT","text":"hello"}]
                  }]
                  """
                      .formatted(IDEMPOTENCY_KEY))
              .replace(
                  "\"owner\":{\"type\":\"CHAT\",\"chatId\":\"" + OWNER_ID + "\"}",
                  "\"owner\":{\"type\":\"ISSUE_AGENT\",\"issueId\":\""
                      + OWNER_ID
                      + "\",\"agentName\":\"default-assistant\"}");
      mockMvc
          .perform(
              post("/api/harness/command-batches")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(request))
          .andExpect(status().isConflict());
    }
    verifyNoInteractions(acceptanceService, runtime);
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
          "owner":{"type":"CHAT","chatId":"%s"},
          "target":%s,
          "commands":%s
        }
        """
        .formatted(OWNER_ID, target, commands);
  }

  private static String rootSettings() {
    // environmentName 是 required-nullable 字段：未选择 Environment 时必须显式输出 null。
    return """
        {
          "agentName":"default-assistant",
          "model":{"providerName":"openai","modelName":"gpt-5","variant":"default"},
          "environmentName":null
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

  private static String newThreadTarget() {
    return """
        {
          "type":"NEW_THREAD",
          "sessionId":"%s",
          "startEntryId":"%s",
          "threadId":"%s",
          "yoloEnabled":false
        }
        """
        .formatted(SESSION_ID, ENTRY_ID, THREAD_ID);
  }
}
