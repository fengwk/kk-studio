package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;
import java.util.UUID;

/**
 * 既有 Thread 的唯一通用命令写入口 HTTP 契约：path 定位 Thread、请求体只携带精确 cursor 与有序命令，不再携带 owner/target；202 只表示
 * durable acceptance 已提交，响应回读接受后的权威 Thread snapshot。
 */
class StudioHarnessThreadCommandBatchControllerTest {

  /** 请求 path 的 Thread（客户端预分配身份）。 */
  private static final String THREAD_ID = "00000000-0000-0000-0000-000000000012";

  /** 精确 CAS cursor 的 head Entry。 */
  private static final String HEAD_ENTRY_ID = "00000000-0000-0000-0000-000000000013";

  private static final String COMMAND_KEY = "00000000-0000-0000-0000-000000000014";

  /** fixture 的权威 Thread id：接受后回读的 snapshot，与 path 的客户端 threadId 是不同事实。 */
  private static final String AUTHORITATIVE_THREAD_ID = "00000000-0000-0000-0000-000000000001";

  private HarnessCommandAcceptanceOrchestrator acceptanceService;
  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    acceptanceService = mock(HarnessCommandAcceptanceOrchestrator.class);
    runtime = mock(HarnessRuntime.class);
    StudioHarnessThreadCommandBatchController controller =
        new StudioHarnessThreadCommandBatchController(acceptanceService, runtime);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
    when(acceptanceService.acceptOnThread(any(AcceptCommandsCommand.class)))
        .thenReturn(
            new AcceptedCommands(
                HarnessRuntimeTestFixtures.session(),
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.thread(UUID.fromString(THREAD_ID)),
                List.of(HarnessRuntimeTestFixtures.queuedUserMessageCommand()),
                false));
    when(runtime.getThreadSnapshot(any())).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());
  }

  /** 成功：owner-free 请求体映射为 path Thread + 精确 cursor 的 THREAD target，响应回读接受后的权威 Thread。 */
  @Test
  void acceptsOwnerFreeThreadBatchFromThePathAndMapsCurrentSnapshotResponse() throws Exception {
    postBatch(THREAD_ID, batch(userMessageCommand(COMMAND_KEY)))
        .andExpect(status().isAccepted())
        .andExpect(
            jsonPath("$.data.session.sessionId").value("00000000-0000-0000-0000-000000000001"))
        .andExpect(jsonPath("$.data.session.name").value("session"))
        .andExpect(jsonPath("$.data.rootEntry.entryType").value("ROOT"))
        .andExpect(jsonPath("$.data.thread.threadId").value(AUTHORITATIVE_THREAD_ID))
        .andExpect(jsonPath("$.data.thread.name").value("thread"))
        .andExpect(jsonPath("$.data.acceptedCommands", hasSize(1)))
        .andExpect(jsonPath("$.data.acceptedCommands[0].type").value("USER_MESSAGE"))
        .andExpect(jsonPath("$.data.replayed").value(false));

    ArgumentCaptor<AcceptCommandsCommand> commandCaptor =
        ArgumentCaptor.forClass(AcceptCommandsCommand.class);
    verify(acceptanceService).acceptOnThread(commandCaptor.capture());
    AcceptCommandsTarget.Thread target =
        assertInstanceOf(AcceptCommandsTarget.Thread.class, commandCaptor.getValue().target());
    assertEquals(UUID.fromString(THREAD_ID), target.threadId());
    assertEquals(UUID.fromString(HEAD_ENTRY_ID), target.expectedHeadEntryId());
    assertEquals(7L, target.expectedNextCommandSequence());
    List<ThreadCommandType> acceptedTypes =
        commandCaptor.getValue().commands().stream()
            .map(command -> command.payload().type())
            .toList();
    assertEquals(List.of(ThreadCommandType.USER_MESSAGE), acceptedTypes);
    assertEquals(
        UUID.fromString(COMMAND_KEY), commandCaptor.getValue().commands().get(0).idempotencyKey());

    // 既有 Thread 的继续写入只走 acceptOnThread，不借 owner-aware 创建入口，也不回读 snapshot 之外的状态。
    verify(acceptanceService, never())
        .accept(any(OwnerRef.class), any(AcceptCommandsCommand.class));
    verify(runtime).getThreadSnapshot(UUID.fromString(AUTHORITATIVE_THREAD_ID));
  }

  /** 请求体必须完全 owner-free：多携带历史 owner / target 字段即未知字段，400 且不触达 acceptance service 与 runtime。 */
  @Test
  void rejectsOwnerOrTargetFieldInBodyBeforeTouchingAcceptanceServiceOrRuntime() throws Exception {
    String ownerField =
        """
        {"type":"CHAT","chatId":"%s"}
        """.formatted(HEAD_ENTRY_ID);
    assertUnknownBatchFieldRejected("owner", ownerField);
    assertUnknownBatchFieldRejected(
        "target",
        """
        {
          "type":"NEW_THREAD",
          "sessionId":"%s",
          "startEntryId":"%s",
          "threadId":"%s",
          "yoloEnabled":false
        }
        """
            .formatted(THREAD_ID, HEAD_ENTRY_ID, HEAD_ENTRY_ID));

    verifyNoInteractions(acceptanceService, runtime);
  }

  /**
   * cursor 必须严格：path threadId / head Entry 非 canonical UUID，或 next command sequence 非正 decimal，一律
   * 400。
   */
  @Test
  void rejectsMalformedCursorBeforeTouchingAcceptanceServiceOrRuntime() throws Exception {
    String commands = userMessageCommand(COMMAND_KEY);

    postBatch("not-a-uuid", batch(commands)).andExpect(status().isBadRequest());
    for (String malformedUuid :
        List.of("00000000-0000-0000-0000-00000000001", "00000000000000000000000000000013", "")) {
      postBatch(
              THREAD_ID,
              batchWithCursor("\"expectedHeadEntryId\":\"" + malformedUuid + "\"", commands))
          .andExpect(status().isBadRequest());
    }
    for (String malformedSequence : List.of("0", "-1", "1.5", "abc", "")) {
      postBatch(
              THREAD_ID,
              batchWithCursor(
                  "\"expectedNextCommandSequence\":\"" + malformedSequence + "\"", commands))
          .andExpect(status().isBadRequest());
    }

    // cursor 字段缺失是独立事实，绝不静默回退默认值。
    postBatch(
            THREAD_ID,
            """
            {"expectedNextCommandSequence":"7","commands":%s}
            """
                .formatted(commands))
        .andExpect(status().isBadRequest());
    postBatch(
            THREAD_ID,
            """
            {"expectedHeadEntryId":"%s","commands":%s}
            """
                .formatted(HEAD_ENTRY_ID, commands))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(acceptanceService, runtime);
  }

  /** 命令列表必须非空：空数组、显式 null 与字段缺失一律 400，且不触达 acceptance service 与 runtime。 */
  @Test
  void rejectsEmptyCommandsBeforeTouchingAcceptanceServiceOrRuntime() throws Exception {
    postBatch(THREAD_ID, batch("[]")).andExpect(status().isBadRequest());
    postBatch(
            THREAD_ID,
            """
            {"expectedHeadEntryId":"%s","expectedNextCommandSequence":"7"}
            """
                .formatted(HEAD_ENTRY_ID))
        .andExpect(status().isBadRequest());
    postBatch(
            THREAD_ID,
            """
            {"expectedHeadEntryId":"%s","expectedNextCommandSequence":"7","commands":null}
            """
                .formatted(HEAD_ENTRY_ID))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(acceptanceService, runtime);
  }

  /** CUSTOM_MESSAGE / NOTIFICATION / SET_CONTRIBUTOR_STATE 是内部 steering，产品 HTTP surface 一律 400。 */
  @Test
  void rejectsInternalOnlyCommandTypesBeforeTouchingAcceptanceServiceOrRuntime() throws Exception {
    for (String type : List.of("CUSTOM_MESSAGE", "NOTIFICATION", "SET_CONTRIBUTOR_STATE")) {
      String commands =
          """
          [{
            "type":"%s",
            "idempotencyKey":"%s"
          }]
          """
              .formatted(type, COMMAND_KEY);
      postBatch(THREAD_ID, batch(commands)).andExpect(status().isBadRequest());
    }

    verifyNoInteractions(acceptanceService, runtime);
  }

  /** 类型化拒绝经统一翻译：orchestrator 拒绝 400、缺失 Thread 404、业务冲突 409 且保留 reason，全程不回读 snapshot。 */
  @Test
  void translatesTypedRejectionsFromAcceptOnThread() throws Exception {
    when(acceptanceService.acceptOnThread(any(AcceptCommandsCommand.class)))
        .thenThrow(new IllegalArgumentException("thread command batch rejected"));
    postBatch(THREAD_ID, batch(userMessageCommand(COMMAND_KEY)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.detail").value("thread command batch rejected"));

    when(acceptanceService.acceptOnThread(any(AcceptCommandsCommand.class)))
        .thenThrow(new HarnessRuntimeNotFoundException("thread not found"));
    postBatch(THREAD_ID, batch(userMessageCommand(COMMAND_KEY)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errors.detail").value("thread not found"));

    when(acceptanceService.acceptOnThread(any(AcceptCommandsCommand.class)))
        .thenThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.IDEMPOTENCY_KEY_REUSED,
                "client command id was reused"));
    postBatch(THREAD_ID, batch(userMessageCommand(COMMAND_KEY)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors.reason").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.errors.detail").value("client command id was reused"));

    // 类型化拒绝路径不追加 snapshot 回读；仅有根身份判定这一只读探测。
    verify(runtime, never()).getThreadSnapshot(any());
  }

  /**
   * 测试意图：自由输入/Goal/设置等人工写入只允许指向执行根；子 Thread 在映射通过后、进入 acceptance 之前以 409 拒绝，避免把根级输入落到观察树内部。内部
   * task/resume 不走本 HTTP 边界。
   */
  @Test
  void rejectsChildThreadManualBatchAsConflictBeforeAcceptance() throws Exception {
    UUID child = UUID.fromString(THREAD_ID);
    UUID root = UUID.randomUUID();
    when(runtime.findAncestorChain(child)).thenReturn(List.of(child, root));

    postBatch(THREAD_ID, batch(userMessageCommand(COMMAND_KEY))).andExpect(status().isConflict());

    verifyNoInteractions(acceptanceService);
  }

  private ResultActions postBatch(String threadId, String body) throws Exception {
    return mockMvc.perform(
        post("/api/harness/threads/" + threadId + "/command-batches")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private void assertUnknownBatchFieldRejected(String field, String extraValue) throws Exception {
    String body =
        """
        {
          "expectedHeadEntryId":"%s",
          "expectedNextCommandSequence":"7",
          "%s":%s,
          "commands":%s
        }
        """
            .formatted(HEAD_ENTRY_ID, field, extraValue, userMessageCommand(COMMAND_KEY));
    postBatch(THREAD_ID, body)
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors.detail").value("unknown thread command batch field: " + field));
  }

  private static String batch(String commands) {
    return batchWithCursor(
        "\"expectedHeadEntryId\":\"%s\",\"expectedNextCommandSequence\":\"7\""
            .formatted(HEAD_ENTRY_ID),
        commands);
  }

  /** 组装 owner-free 请求体：cursorFields 是完整或部分的 cursor JSON 片段，commands 是命令数组。 */
  private static String batchWithCursor(String cursorFields, String commands) {
    return """
        {
          %s,
          "commands":%s
        }
        """
        .formatted(cursorFields, commands);
  }

  private static String userMessageCommand(String idempotencyKey) {
    return """
        [{
          "type":"USER_MESSAGE",
          "idempotencyKey":"%s",
          "contents":[{"type":"TEXT","text":"hello"}]
        }]
        """
        .formatted(idempotencyKey);
  }
}
