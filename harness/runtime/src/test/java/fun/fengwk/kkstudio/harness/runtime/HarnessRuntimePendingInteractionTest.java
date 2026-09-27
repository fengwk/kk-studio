package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.ASK_USER_QUESTIONNAIRE;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.ToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.mappedAssistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.modelInvocationWithRequest;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.parkForInput;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.responseWithToolCalls;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedAskUserBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.tooledModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code listPendingInteractions}：待处理交互（WAITING_INPUT / WAITING_APPROVAL）的稳定分页与投影事实。
 *
 * <p>测试意图按业务边界分组：（1）待处理列表只返回 WAITING_INPUT 与 WAITING_APPROVAL，严格按 (createdAt, id) 升序 keyset 分页；
 * （2）每页 limit 准确生效，hasMore 标志由 storage 多取一条判定，且翻页时严格排除游标行、页间无重复； （3）投影事实保持精确：WAITING_INPUT 携带
 * argumentsJson 且 approvalJson 为 null，WAITING_APPROVAL 携带 approvalJson； （4）limit 非法（<= 0）或游标参数为
 * null 时严格校验抛出异常。
 */
class HarnessRuntimePendingInteractionTest {

  private InMemoryHarnessStore store;
  private TestClock clock;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    clock = new TestClock(T5);
    runtime = HarnessRuntimeTestSupport.runtime(store, clock);
  }

  /** 在指定时间戳创建带有 READY ToolInvocation 的 TOOL baseline。 */
  private static ToolBaseline seedToolBaselineAt(InMemoryHarnessStore store, Instant createdAt) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(
              turnStartEntry(turnStartEntryId, sessionId, rootEntryId, createdAt, threadId));
          ThreadState thread = thread(threadId, sessionId, turnStartEntryId);
          tx.insertThread(thread);
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, createdAt));
          ModelRequestSpec requestSpec = tooledModelRequest(List.of("bash"));
          ProviderResponse response = responseWithToolCalls("call-1");
          UUID assistantEntryId = tx.nextId();
          tx.insertEntry(
              mappedAssistantEntry(
                  assistantEntryId, sessionId, userEntryId, createdAt, requestSpec, response));
          UUID modelId = tx.nextId();
          ModelInvocation model =
              modelInvocationWithRequest(
                  modelId, threadId, turnStartEntryId, turnStartEntryId, requestSpec, createdAt);
          tx.insertModelInvocation(model);
          ModelInvocation succeeded =
              model.beginDispatch(createdAt).markRunning(createdAt).succeed(response, createdAt);
          tx.updateModelInvocation(model.beginDispatch(createdAt));
          tx.updateModelInvocation(model.beginDispatch(createdAt).markRunning(createdAt));
          tx.updateModelInvocation(succeeded);
          tx.updateModelInvocation(succeeded.attachResultEntry(assistantEntryId, createdAt));
          UUID toolId = tx.nextId();
          tx.insertToolInvocations(
              List.of(toolInvocation(toolId, modelId, assistantEntryId, 0, "call-1", createdAt)));
          tx.updateThread(thread.advanceHead(assistantEntryId, createdAt));
          return new ToolBaseline(
              sessionId,
              rootEntryId,
              turnStartEntryId,
              threadId,
              assistantEntryId,
              modelId,
              toolId);
        });
  }

  /**
   * 待处理交互列表按 (createdAt, id) 稳定分页：limit=1 时首页返回最早记录且 hasMore=true， 将首页游标传入下一页时严格排除已返回项，第二页返回剩余记录且
   * hasMore 为 false，页间无重复。
   */
  @Test
  void listPendingInteractionsKeysetPaginationAndCursorAdvancement() {
    ToolBaseline inputBaseline = seedAskUserBaseline(store); // createdAt = T1
    parkForInput(store, inputBaseline, T3);

    ToolBaseline approvalBaseline = seedToolBaselineAt(store, T2); // createdAt = T2
    setWaitingApproval(store, approvalBaseline);

    // 1. 首页 limit=1：必须返回按 (createdAt, id) 最早的一条（inputBaseline，createdAt=T1 < T2），且 hasMore 为 true
    PendingInteractionPage page1 =
        runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 1);
    assertEquals(1, page1.interactions().size());
    assertTrue(page1.hasMore());
    PendingInteraction first = page1.interactions().get(0);
    assertEquals(inputBaseline.toolId(), first.invocationId());
    assertEquals(T1, first.createdAt());

    // 2. 第二页：传入上一页末尾的 (createdAt, invocationId) 作为游标，严格排除第一条，返回第二条且 hasMore 为 false
    PendingInteractionPage page2 =
        runtime.listPendingInteractions(first.createdAt(), first.invocationId(), 1);
    assertEquals(1, page2.interactions().size());
    assertFalse(page2.hasMore());
    PendingInteraction second = page2.interactions().get(0);
    assertEquals(approvalBaseline.toolId(), second.invocationId());
    assertEquals(T2, second.createdAt());

    // 页间无重复
    assertFalse(first.invocationId().equals(second.invocationId()));

    // 3. 第三页：继续以第二条作为游标，应为空且 hasMore 为 false
    PendingInteractionPage page3 =
        runtime.listPendingInteractions(second.createdAt(), second.invocationId(), 1);
    assertEquals(0, page3.interactions().size());
    assertFalse(page3.hasMore());
  }

  /** limit 超过总待处理数时，单次查询返回所有等待交互，按 (createdAt, id) 排序且 hasMore 为 false。 */
  @Test
  void listPendingInteractionsLimitExceedingTotalReturnsAllWithHasMoreFalse() {
    ToolBaseline inputBaseline = seedAskUserBaseline(store); // createdAt = T1
    parkForInput(store, inputBaseline, T3);

    ToolBaseline approvalBaseline = seedToolBaselineAt(store, T2); // createdAt = T2
    setWaitingApproval(store, approvalBaseline);

    PendingInteractionPage page =
        runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 10);
    assertEquals(2, page.interactions().size());
    assertFalse(page.hasMore());

    assertEquals(inputBaseline.toolId(), page.interactions().get(0).invocationId());
    assertEquals(approvalBaseline.toolId(), page.interactions().get(1).invocationId());
  }

  /**
   * 投影事实准确性： WAITING_INPUT 携带 non-null argumentsJson 与 null approvalJson； WAITING_APPROVAL 携带
   * non-null approvalJson； threadId、sessionId、toolCallId、toolName 均与底层调用事实一致。
   */
  @Test
  void listPendingInteractionsProjectsCorrectWaitingFacts() {
    ToolBaseline inputBaseline = seedAskUserBaseline(store); // createdAt = T1
    parkForInput(store, inputBaseline, T3);

    ToolBaseline approvalBaseline = seedToolBaselineAt(store, T2); // createdAt = T2
    setWaitingApproval(store, approvalBaseline);

    PendingInteractionPage page =
        runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 10);
    assertEquals(2, page.interactions().size());

    PendingInteraction inputInteraction = page.interactions().get(0);
    assertEquals(inputBaseline.toolId(), inputInteraction.invocationId());
    assertEquals(inputBaseline.threadId(), inputInteraction.threadId());
    assertEquals(inputBaseline.sessionId(), inputInteraction.sessionId());
    assertEquals(ToolInvocationStatus.WAITING_INPUT, inputInteraction.status());
    assertEquals("call-1", inputInteraction.toolCallId());
    assertEquals("ask_user", inputInteraction.toolName());
    assertEquals(ASK_USER_QUESTIONNAIRE, inputInteraction.argumentsJson());
    assertNull(inputInteraction.approvalJson());
    assertEquals(T1, inputInteraction.createdAt());

    PendingInteraction approvalInteraction = page.interactions().get(1);
    assertEquals(approvalBaseline.toolId(), approvalInteraction.invocationId());
    assertEquals(approvalBaseline.threadId(), approvalInteraction.threadId());
    assertEquals(approvalBaseline.sessionId(), approvalInteraction.sessionId());
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, approvalInteraction.status());
    assertEquals("call-1", approvalInteraction.toolCallId());
    assertEquals("bash", approvalInteraction.toolName());
    assertEquals("{}", approvalInteraction.argumentsJson());
    assertNotNull(approvalInteraction.approvalJson());
    assertTrue(approvalInteraction.approvalJson().contains("tool approval requested"));
    assertEquals(T2, approvalInteraction.createdAt());
  }

  /** 参数校验：limit <= 0 抛出 IllegalArgumentException，游标为 null 抛出 NullPointerException。 */
  @Test
  void listPendingInteractionsValidatesArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), -1));
    assertThrows(
        NullPointerException.class,
        () -> runtime.listPendingInteractions(null, new UUID(0L, 0L), 10));
    assertThrows(
        NullPointerException.class, () -> runtime.listPendingInteractions(Instant.EPOCH, null, 10));
  }
}
