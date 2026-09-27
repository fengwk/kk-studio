package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.COMPACTION_CONFIG;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.branchSettings;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.AutomaticCompactionPlanner;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextProbe;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.util.List;
import java.util.UUID;

/**
 * 线程空闲递归传播与 Join 匹配交付测试。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>子线程快速完成（rapid child completion）-> 匹配 join -> 向父线程原子入队 CUSTOM_MESSAGE XML -> 父置为 ACTIVE 并请求
 *       THREAD work；
 *   <li>同子线程多 Join 匹配（multiple joins）-> 按时间确定性序匹配并为父线程分配连续 sequence；
 *   <li>等待子线程（waiting children）-> 多子线程并存时父保持 WAITING_CHILDREN，当最后子线程空闲时父递归变为 IDLE；
 *   <li>无 Join 子孙多层递归（no join descendant propagation）-> 深度祖先链（GrandChild -> Child -> Parent ->
 *       Root）逐级向上空闲；
 *   <li>父线程处于 STOPPED 边界时（parent stopped receipt）-> 冻结结果为 pending delivery，不入队父命令；
 *   <li>Root 票据（root ticket）-> 根线程空闲时正常匹配无父级的 completion ticket；
 *   <li>子线程失败或拒绝（error / rejected）-> 正常匹配 join 并渲染 error 凭据 XML 交付父级。
 * </ul>
 */
class ThreadProcessorIdleJoinDeliveryTest extends ThreadProcessorTestBase {

  @Test
  void rapidChildCompletionMatchesJoinDeliversCustomMessageToParentAndSetsParentActive() {
    // 测试意图：验证子线程从启动到模型完成关闭 turn 时，在同一事务内完成子线程变 IDLE、Join 匹配、
    // 父线程投递 CUSTOM_MESSAGE XML 消息、父线程变为 ACTIVE、请求父线程 THREAD Work，并可在后续 claim 中被父线程消费。
    Fixture fixture = fixture();
    fixture.resolver.autoConsistent = true;
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    // 种子：子线程收到用户指令，并附加一条 Join 记录
    seedCommand(
        fixture.store, childId, new UserMessageCommandPayload(userMessage("calculate 1+1")));
    requestThreadWork(fixture.store, childId);

    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "calculator-agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    // Claim 1: 子线程启动 INPUT Turn，创建 ModelInvocation，请求 MODEL Work
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));
    ModelInvocation model =
        fixture.store.transaction(
            tx -> {
              EntryPath childPath =
                  tx.loadEntryPath(tx.findThread(childId).orElseThrow().headEntryId());
              UUID turnStartId = childPath.openTurnStart().orElseThrow().id();
              return tx.findModelInvocationByTurn(childId, turnStartId).orElseThrow();
            });

    // 模拟模型执行成功并写回 SUCCEEDED 状态
    transitionModelToSucceeded(fixture.store, model, "2");
    requestThreadWork(fixture.store, childId);

    // Claim 2: 子线程应用 terminal model，关闭 Turn，达到真正的递归 IDLE：
    // 在同事务内：子线程变为 IDLE、Join 匹配、父线程被投递 CUSTOM_MESSAGE XML 且变为 ACTIVE
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));

    ThreadState childState = thread(fixture.store, childId);
    assertEquals(ThreadLifecycleStatus.IDLE, childState.status());

    ThreadJoin matchedJoin =
        fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertTrue(matchedJoin.matched());
    assertEquals(childState.version(), matchedJoin.matchedIdleVersion());
    assertEquals(childState.headEntryId(), matchedJoin.resultHeadEntryId());
    assertEquals(1L, matchedJoin.deliveryCommandSequence());

    // 验证父线程状态与队列
    ThreadState parentState = thread(fixture.store, parentId);
    assertEquals(ThreadLifecycleStatus.ACTIVE, parentState.status());
    assertEquals(2L, parentState.nextCommandSequence());

    List<ThreadCommand> parentQueued =
        fixture.store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertEquals(1, parentQueued.size());
    ThreadCommand deliveryCmd = parentQueued.get(0);
    assertEquals(ThreadCommandType.CUSTOM_MESSAGE, deliveryCmd.type());
    assertEquals(invocationId, deliveryCmd.idempotencyKey());

    CustomMessageCommandPayload payload = (CustomMessageCommandPayload) deliveryCmd.payload();
    TextMessageContent textContent = (TextMessageContent) payload.message().contents().get(0);
    assertTrue(
        textContent
            .text()
            .contains(
                "<subagent_result thread_id=\""
                    + childId
                    + "\" agent=\"calculator-agent\" state=\"completed\">"));
    assertTrue(textContent.text().contains("<result>\nresponse text\n</result>"));

    // 验证父线程已请求 THREAD Work
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, parentId)));

    // Claim 3: 父线程消费该 CUSTOM_MESSAGE 启动父级 INPUT Turn
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(parentId));
    EntryPath parentPath = path(fixture.store, parentId);
    assertTrue(parentPath.openTurnStart().isPresent());
  }

  @Test
  void multipleJoinsOnSameChildMatchInOrderAndDeliverConsecutiveCommandsToParent() {
    // 测试意图：验证当同一子线程存在多条针对相同源命令的未匹配 Join 凭据时，子线程空闲时按 (createdAt, invocationId)
    // 顺序同时匹配，并以连续递增的 sequence 投递给父线程。
    Fixture fixture = fixture();
    fixture.resolver.autoConsistent = true;
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("task-1")));

    UUID inv1 = UUID.randomUUID();
    UUID inv2 = UUID.randomUUID();

    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  inv1,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "agent-1",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          tx.insertJoin(
              new ThreadJoin(
                  inv2,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "agent-2",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW.plusMillis(10),
                  NOW.plusMillis(10)));
          return null;
        });

    requestThreadWork(fixture.store, childId);

    // 执行子线程 turn
    fixture.nextClaim(childId);
    ModelInvocation model =
        fixture.store.transaction(
            tx -> {
              EntryPath childPath =
                  tx.loadEntryPath(tx.findThread(childId).orElseThrow().headEntryId());
              UUID turnStartId = childPath.openTurnStart().orElseThrow().id();
              return tx.findModelInvocationByTurn(childId, turnStartId).orElseThrow();
            });
    transitionModelToSucceeded(fixture.store, model, "all done");
    requestThreadWork(fixture.store, childId);

    // 子线程完成
    fixture.nextClaim(childId);

    ThreadJoin join1 = fixture.store.transaction(tx -> tx.findJoin(inv1).orElseThrow());
    ThreadJoin join2 = fixture.store.transaction(tx -> tx.findJoin(inv2).orElseThrow());

    assertTrue(join1.matched());
    assertTrue(join2.matched());
    assertEquals(1L, join1.deliveryCommandSequence());
    assertEquals(2L, join2.deliveryCommandSequence());

    ThreadState parent = thread(fixture.store, parentId);
    assertEquals(ThreadLifecycleStatus.ACTIVE, parent.status());
    assertEquals(3L, parent.nextCommandSequence());

    List<ThreadCommand> parentQueued =
        fixture.store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertEquals(2, parentQueued.size());
    assertEquals(1L, parentQueued.get(0).sequence());
    assertEquals(2L, parentQueued.get(1).sequence());
  }

  @Test
  void waitingChildrenPropagationParentTransitionsToIdleWhenLastChildBecomesIdle() {
    // 测试意图：验证父线程存在多个直接子线程时，部分子线程空闲后父级保持 WAITING_CHILDREN，
    // 直到最后一个子线程变为空闲，父级才在同事务内原子转换为 IDLE。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID child1 =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);
    UUID child2 =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    // 验证初始状态：父级有 2 个活跃孩子
    fixture.store.transaction(
        tx -> {
          assertEquals(2, tx.countActiveChildren(parentId));
          return null;
        });

    // 让 child1 变为空闲
    fixture.store.transaction(
        tx -> {
          ThreadState c1 = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, child1);
          new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock)
              .propagateIdle(tx, c1, NOW);
          return null;
        });

    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, child1).status());
    // child2 仍活跃，父级仍为 WAITING_CHILDREN
    assertEquals(ThreadLifecycleStatus.WAITING_CHILDREN, thread(fixture.store, parentId).status());

    // 让 child2 变为空闲
    fixture.store.transaction(
        tx -> {
          ThreadState c2 = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, child2);
          new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock)
              .propagateIdle(tx, c2, NOW);
          return null;
        });

    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, child2).status());
    // 所有孩子均空闲，父级自动推进为 IDLE
    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, parentId).status());
  }

  @Test
  void multiLevelHierarchyPropagationWithoutJoinsPropagatesRecursivelyToRoot() {
    // 测试意图：验证四层执行树（Root -> Parent -> Child -> GrandChild）在无任何 Join 凭据的情况下，
    // 最底层 GrandChild 达到 IDLE 时，能够沿祖先链逐级回溯，使整个执行树的所有层级均原子达到 IDLE。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID rootId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID parentId =
        createThread(
            fixture.store, sessionId, rootId, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(
            fixture.store,
            sessionId,
            parentId,
            rootEntryId,
            ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID grandChildId =
        createThread(fixture.store, sessionId, childId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    // 最底层 grandChild 变为空闲
    fixture.store.transaction(
        tx -> {
          ThreadState gc = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, grandChildId);
          new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock)
              .propagateIdle(tx, gc, NOW);
          return null;
        });

    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, grandChildId).status());
    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, childId).status());
    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, parentId).status());
    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, rootId).status());
  }

  @Test
  void parentStoppedReceiptIsHeldAndNotDeliveredUntilNextInput() {
    // 测试意图：验证当父线程处于 STOPPED 边界时（head 为 TurnEndPayload STOPPED），子线程空闲时正常匹配 Join，
    // 但凭据交付被冻结（deliveryCommandSequence 保持 null，不向父线程插入命令且不唤醒父线程）。
    Fixture fixture = fixture();
    fixture.resolver.autoConsistent = true;
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);

    // 创建处于 STOPPED 边界的父线程：STOP TurnStart -> AssistantError CANCELLED -> TurnEnd STOPPED
    UUID stoppedTurnStartId = UUID.randomUUID();
    UUID stoppedAssistantErrorId = UUID.randomUUID();
    UUID stoppedTurnEndId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  stoppedTurnStartId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.STOP, branchSettings(), parentId, null, null, null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  stoppedAssistantErrorId,
                  sessionId,
                  stoppedTurnStartId,
                  new AssistantErrorPayload(
                      new AssistantError("CANCELLED", "Cancelled by user"), null),
                  NOW));
          tx.insertEntry(
              new Entry(
                  stoppedTurnEndId,
                  sessionId,
                  stoppedAssistantErrorId,
                  new TurnEndPayload(
                      stoppedTurnStartId,
                      TurnEndOutcome.STOPPED,
                      false,
                      TurnEndReason.USER_STOP,
                      UUID.randomUUID()),
                  NOW));
          ThreadState p = tx.lockThread(parentId).orElseThrow();
          tx.updateThread(p.advanceHead(stoppedTurnEndId, NOW));
          return null;
        });

    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("task")));
    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    requestThreadWork(fixture.store, childId);

    // 子线程完成 turn
    fixture.nextClaim(childId);
    ModelInvocation model =
        fixture.store.transaction(
            tx -> {
              EntryPath childPath =
                  tx.loadEntryPath(tx.findThread(childId).orElseThrow().headEntryId());
              UUID turnStartId = childPath.openTurnStart().orElseThrow().id();
              return tx.findModelInvocationByTurn(childId, turnStartId).orElseThrow();
            });
    transitionModelToSucceeded(fixture.store, model, "result");
    requestThreadWork(fixture.store, childId);

    // 子线程执行 terminal apply 并达到 IDLE
    fixture.nextClaim(childId);

    ThreadJoin join = fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertTrue(join.matched());
    // 交付被 hold：deliveryCommandSequence 仍为 null
    assertNull(join.deliveryCommandSequence());

    // 父线程未收到新命令，未请求 THREAD Work
    List<ThreadCommand> parentCommands =
        fixture.store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertTrue(parentCommands.isEmpty());
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, parentId)));

    // 存储层 pending deliveries 包含此 join
    List<ThreadJoin> pending = fixture.store.transaction(tx -> tx.loadPendingDeliveries(parentId));
    assertEquals(1, pending.size());
    assertEquals(invocationId, pending.get(0).invocationId());
  }

  @Test
  void rootTicketMatchedWhenRootThreadBecomesIdle() {
    // 测试意图：验证无父级的 Root completion ticket（parentThreadId == null）在根线程变为空闲时能够被正常匹配，且不触发父级交付。
    Fixture fixture = fixture();
    fixture.resolver.autoConsistent = true;
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID rootId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.ACTIVE);
    seedCommand(fixture.store, rootId, new UserMessageCommandPayload(userMessage("root-task")));

    UUID ticketId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(rootId);
          tx.insertJoin(
              new ThreadJoin(
                  ticketId,
                  CREATION_REQUEST_HASH,
                  null,
                  rootId,
                  1L,
                  0L,
                  "root-agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    requestThreadWork(fixture.store, rootId);

    // 根线程执行 turn
    fixture.nextClaim(rootId);
    ModelInvocation model =
        fixture.store.transaction(
            tx -> {
              EntryPath rootPath =
                  tx.loadEntryPath(tx.findThread(rootId).orElseThrow().headEntryId());
              UUID turnStartId = rootPath.openTurnStart().orElseThrow().id();
              return tx.findModelInvocationByTurn(rootId, turnStartId).orElseThrow();
            });
    transitionModelToSucceeded(fixture.store, model, "root-result");
    requestThreadWork(fixture.store, rootId);

    // 根线程完成 turn
    fixture.nextClaim(rootId);

    ThreadState rootState = thread(fixture.store, rootId);
    assertEquals(ThreadLifecycleStatus.IDLE, rootState.status());

    ThreadJoin ticket = fixture.store.transaction(tx -> tx.findJoin(ticketId).orElseThrow());
    assertTrue(ticket.matched());
    assertEquals(rootState.version(), ticket.matchedIdleVersion());
    assertEquals(rootState.headEntryId(), ticket.resultHeadEntryId());
    assertNull(ticket.deliveryCommandSequence());
  }

  @Test
  void childTurnErrorDeliversErrorOutcomeXmlToParent() {
    // 测试意图：验证子线程模型执行失败（FAILED TurnEnd）后达到 IDLE 时，Join 匹配投影出 ThreadJoinOutcome.ERROR，
    // 并渲染带有 <error> 的 XML 消息投递给父线程。
    Fixture fixture = fixture();
    fixture.resolver.autoConsistent = true;
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    seedCommand(fixture.store, childId, new UserMessageCommandPayload(userMessage("failing-task")));
    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "coder",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    requestThreadWork(fixture.store, childId);

    fixture.nextClaim(childId);
    ModelInvocation model =
        fixture.store.transaction(
            tx -> {
              EntryPath childPath =
                  tx.loadEntryPath(tx.findThread(childId).orElseThrow().headEntryId());
              UUID turnStartId = childPath.openTurnStart().orElseThrow().id();
              return tx.findModelInvocationByTurn(childId, turnStartId).orElseThrow();
            });

    // 模拟模型执行失败
    transitionModelToFailed(fixture.store, model, "upstream rate limit exceeded");
    requestThreadWork(fixture.store, childId);

    // 子线程完成失败 apply
    fixture.nextClaim(childId);

    ThreadJoin join = fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertTrue(join.matched());
    assertEquals(1L, join.deliveryCommandSequence());

    List<ThreadCommand> parentQueued =
        fixture.store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertEquals(1, parentQueued.size());
    CustomMessageCommandPayload payload =
        (CustomMessageCommandPayload) parentQueued.get(0).payload();
    TextMessageContent textContent = (TextMessageContent) payload.message().contents().get(0);
    assertTrue(textContent.text().contains("state=\"error\""));
    assertTrue(textContent.text().contains("<error>"));
  }

  @Test
  void rejectedTurnDeliversErrorXmlToParent() {
    // 测试意图：验证子线程 TurnResolver 拒绝时，commitTx 生成 FAILED TurnEnd 并直接在空闲时匹配 Join 交付父级。
    Fixture fixture = fixture();
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("MODEL_REJECTED", "budget exhausted")));
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    seedCommand(
        fixture.store, childId, new UserMessageCommandPayload(userMessage("rejected-task")));
    UUID invocationId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.lockThread(childId);
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    requestThreadWork(fixture.store, childId);

    // Claim 1: resolve and commit -> rejected -> commitTx writes TurnEnd FAILED and transitions
    // child to IDLE
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(childId));

    ThreadJoin join = fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertTrue(join.matched());
    assertEquals(1L, join.deliveryCommandSequence());

    List<ThreadCommand> parentQueued =
        fixture.store.transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId);
            });
    assertEquals(1, parentQueued.size());
    CustomMessageCommandPayload payload =
        (CustomMessageCommandPayload) parentQueued.get(0).payload();
    TextMessageContent textContent = (TextMessageContent) payload.message().contents().get(0);
    assertTrue(textContent.text().contains("state=\"error\""));
  }

  @Test
  void advanceHeadWithActiveChildrenTransitionsToWaitingChildren() {
    // 测试意图：验证当线程存在活跃子线程时，advanceHeadAndPropagateIdle 推进 head 后
    // 状态原子转换为 WAITING_CHILDREN 并直接返回，不触发空闲收尾与 Join 交付。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    UUID nextHeadId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  nextHeadId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.INPUT, branchSettings(), parentId, null, null, null),
                  NOW));
          ThreadState p = tx.lockThread(parentId).orElseThrow();
          ThreadLifecycleCoordinator coordinator =
              new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock);
          ThreadState advanced = coordinator.advanceHeadAndPropagateIdle(tx, p, nextHeadId, NOW);
          assertEquals(ThreadLifecycleStatus.WAITING_CHILDREN, advanced.status());
          assertEquals(nextHeadId, advanced.headEntryId());
          return null;
        });

    assertEquals(ThreadLifecycleStatus.WAITING_CHILDREN, thread(fixture.store, parentId).status());
  }

  @Test
  void propagateIdleTransitionsAncestorWithLocalWorkToActive() {
    // 测试意图：验证子线程变为空闲沿祖先链向上递归传播时，若祖先线程状态为 WAITING_CHILDREN
    // 但存在未处理本地工作（如排队消息），祖先线程被原子转换为 ACTIVE 并终止向上递归。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    seedCommand(
        fixture.store, parentId, new UserMessageCommandPayload(userMessage("pending parent task")));

    fixture.store.transaction(
        tx -> {
          ThreadState c = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, childId);
          ThreadLifecycleCoordinator coordinator =
              new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock);
          coordinator.propagateIdle(tx, c, NOW);
          return null;
        });

    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, childId).status());
    assertEquals(ThreadLifecycleStatus.ACTIVE, thread(fixture.store, parentId).status());
  }

  @Test
  void propagateIdleTransitionsAncestorWithActiveChildrenToWaitingChildren() {
    // 测试意图：验证当祖先线程处于 ACTIVE 状态且无本地工作，但在某一子线程空闲后仍有其他活跃子线程时，
    // 祖先线程被原子收敛为 WAITING_CHILDREN。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.ACTIVE);
    UUID child1 =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);
    UUID child2 =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    fixture.store.transaction(
        tx -> {
          ThreadState c1 = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, child1);
          ThreadLifecycleCoordinator coordinator =
              new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock);
          coordinator.propagateIdle(tx, c1, NOW);
          return null;
        });

    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, child1).status());
    assertEquals(ThreadLifecycleStatus.WAITING_CHILDREN, thread(fixture.store, parentId).status());
  }

  @Test
  void propagateIdleDeliversJoinAndRequestsWorkForParent() {
    // 测试意图：验证直接调用 propagateIdle 时，若子线程存在 matchable join 且父线程正常活跃，
    // 原子匹配并交付 CUSTOM_MESSAGE 且将父线程 THREAD Work 加入请求列表。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID parentId =
        createThread(
            fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.WAITING_CHILDREN);
    UUID childId =
        createThread(fixture.store, sessionId, parentId, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    UUID invocationId = UUID.randomUUID();
    UUID stopTurnStartId = UUID.randomUUID();
    fixture.store.transaction(
        tx -> {
          tx.insertEntry(
              new Entry(
                  stopTurnStartId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.STOP, branchSettings(), childId, null, null, null),
                  NOW));
          tx.lockThread(childId);
          ThreadCommand cmd =
              new ThreadCommand(
                  childId,
                  1L,
                  new UserMessageCommandPayload(userMessage("task")),
                  UUID.randomUUID(),
                  CREATION_REQUEST_HASH,
                  null,
                  null,
                  null,
                  NOW);
          tx.insertCommands(List.of(cmd));
          tx.loadQueuedCommands(childId);
          tx.updateCommands(List.of(cmd.cancel(stopTurnStartId, NOW)));
          tx.insertJoin(
              new ThreadJoin(
                  invocationId,
                  CREATION_REQUEST_HASH,
                  parentId,
                  childId,
                  1L,
                  0L,
                  "agent",
                  10,
                  0L,
                  null,
                  null,
                  null,
                  NOW,
                  NOW));
          return null;
        });

    fixture.store.transaction(
        tx -> {
          ThreadState c = ThreadLifecycleCoordinator.lockThreadWithAncestors(tx, childId);
          ThreadLifecycleCoordinator coordinator =
              new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock);
          coordinator.propagateIdle(tx, c, NOW);
          return null;
        });

    ThreadJoin join = fixture.store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
    assertTrue(join.matched());
    assertEquals(1L, join.deliveryCommandSequence());
    assertNotNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, parentId)));
  }

  @Test
  void settleStoppedTreeTransitionsActiveThreadToIdle() {
    // 测试意图：验证 settleStoppedTree 结算已被 STOP 屏障切断的执行树时，若线程原为 ACTIVE 且无活跃子线程，
    // 原子收敛为 IDLE 并推进版本与更新时间。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);

    UUID threadId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.ACTIVE);

    fixture.store.transaction(
        tx -> {
          ThreadState activeThread = tx.lockThread(threadId).orElseThrow();
          List<ThreadState> settled =
              ThreadLifecycleCoordinator.settleStoppedTree(tx, List.of(activeThread), NOW);
          assertEquals(1, settled.size());
          assertEquals(ThreadLifecycleStatus.IDLE, settled.get(0).status());
          return null;
        });

    assertEquals(ThreadLifecycleStatus.IDLE, thread(fixture.store, threadId).status());
  }

  @Test
  void hasLocalWorkRecognizesActiveAndPendingContexts() {
    // 测试意图：验证 hasLocalWork 针对 Model/Tool 活跃或终态待处理以及 ContinuationDue 各种非空闲上下文均正确返回 true。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);
    UUID threadId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.IDLE);

    ThreadContextProbe mockProbe = mock(ThreadContextProbe.class);
    Entry dummyEntry =
        new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW);
    UUID turnStartId = UUID.randomUUID();
    UUID turnEndId = UUID.randomUUID();
    Entry continuationTurnEnd =
        new Entry(
            turnEndId,
            sessionId,
            rootEntryId,
            new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, true, null, null),
            NOW);
    ModelInvocation mockModel = mock(ModelInvocation.class);
    when(mockProbe.probe(any(), any(), any()))
        .thenReturn(
            new ThreadContext.ModelActive(mockModel),
            new ThreadContext.ModelTerminalPending(mockModel),
            new ThreadContext.ToolActive(mockModel, dummyEntry, List.of(), List.of()),
            new ThreadContext.ToolTerminalPending(mockModel, dummyEntry, List.of(), List.of()),
            new ThreadContext.ContinuationDue(continuationTurnEnd));

    ThreadLifecycleCoordinator coordinator =
        new ThreadLifecycleCoordinator(
            mockProbe, new AutomaticCompactionPlanner(), () -> COMPACTION_CONFIG, fixture.clock);

    fixture.store.transaction(
        tx -> {
          ThreadState t = tx.lockThread(threadId).orElseThrow();
          for (int i = 0; i < 5; i++) {
            assertTrue(coordinator.hasLocalWork(tx, t));
          }
          return null;
        });
  }

  @Test
  void countActualTurnsReturnsZeroWhenSourceNotAppliedOrMissingFromPath() {
    // 测试意图：验证 countActualTurns 在源命令未应用 TURN_START 或其应用 entry 不在当前分支路径上时安全返回 0；
    // 以及 remindSoftBudgetIfDue 在 join.maxTurns <= 0 或提醒命令已存在时跳过提醒注入。
    Fixture fixture = fixture();
    UUID sessionId = createSession(fixture.store);
    UUID rootEntryId = createRootEntry(fixture.store, sessionId);
    UUID threadId =
        createThread(fixture.store, sessionId, null, rootEntryId, ThreadLifecycleStatus.IDLE);

    UUID missingTurnStartId = UUID.randomUUID();
    ThreadCommand cmdWithMissingStart =
        new ThreadCommand(
            threadId,
            1L,
            new UserMessageCommandPayload(userMessage("task")),
            UUID.randomUUID(),
            CREATION_REQUEST_HASH,
            missingTurnStartId,
            null,
            null,
            NOW);

    ThreadJoin join =
        new ThreadJoin(
            UUID.randomUUID(),
            CREATION_REQUEST_HASH,
            null,
            threadId,
            1L,
            0L,
            "agent",
            5,
            0L,
            null,
            null,
            null,
            NOW,
            NOW);

    EntryPath rootOnlyPath = fixture.store.transaction(tx -> tx.loadEntryPath(rootEntryId));
    assertEquals(
        0,
        ThreadLifecycleCoordinator.countActualTurns(
            List.of(cmdWithMissingStart), rootOnlyPath, join));

    // 测试 remindSoftBudgetIfDue 当 join.maxTurns 为空时跳过
    seedCommand(fixture.store, threadId, new UserMessageCommandPayload(userMessage("task")));
    ThreadJoin nullTurnsJoin =
        new ThreadJoin(
            UUID.randomUUID(),
            CREATION_REQUEST_HASH,
            null,
            threadId,
            1L,
            0L,
            "agent",
            null,
            0L,
            null,
            null,
            null,
            NOW,
            NOW);

    fixture.store.transaction(
        tx -> {
          tx.lockThread(threadId);
          tx.insertJoin(nullTurnsJoin);
          ThreadState t = tx.findThread(threadId).orElseThrow();
          ThreadLifecycleCoordinator coordinator =
              new ThreadLifecycleCoordinator(
                  new ThreadContextProbe(),
                  new AutomaticCompactionPlanner(),
                  () -> COMPACTION_CONFIG,
                  fixture.clock);
          ThreadState after = coordinator.remindSoftBudgetIfDue(tx, t, rootOnlyPath, NOW);
          assertEquals(t.nextCommandSequence(), after.nextCommandSequence());
          return null;
        });
  }

  // -----------------------------------------------------------------------------------------------
  // 辅助构造方法
  // -----------------------------------------------------------------------------------------------

  private static void transitionModelToSucceeded(
      InMemoryHarnessStore store, ModelInvocation model, String text) {
    store.transaction(
        tx -> {
          tx.lockModelInvocation(model.id());
          ModelInvocation dispatched = model.beginDispatch(NOW);
          tx.updateModelInvocation(dispatched);
          ModelInvocation running = dispatched.markRunning(NOW);
          tx.updateModelInvocation(running);
          ModelInvocation succeeded =
              running.succeed(successResponse(List.of(), text), null, null, NOW);
          tx.updateModelInvocation(succeeded);
          return null;
        });
  }

  private static void transitionModelToFailed(
      InMemoryHarnessStore store, ModelInvocation model, String errorText) {
    store.transaction(
        tx -> {
          tx.lockModelInvocation(model.id());
          ModelInvocation dispatched = model.beginDispatch(NOW);
          tx.updateModelInvocation(dispatched);
          ModelInvocation running = dispatched.markRunning(NOW);
          tx.updateModelInvocation(running);
          ModelInvocation failed =
              running.fail(
                  new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, errorText), NOW);
          tx.updateModelInvocation(failed);
          return null;
        });
  }

  private static UUID createSession(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          return sessionId;
        });
  }

  private static UUID createRootEntry(InMemoryHarnessStore store, UUID sessionId) {
    return store.transaction(
        tx -> {
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          return rootEntryId;
        });
  }

  private static UUID createThread(
      InMemoryHarnessStore store,
      UUID sessionId,
      UUID parentThreadId,
      UUID headEntryId,
      ThreadLifecycleStatus status) {
    return store.transaction(
        tx -> {
          UUID threadId = tx.nextId();
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  parentThreadId,
                  headEntryId,
                  CREATION_REQUEST_HASH,
                  "thread-" + threadId,
                  false,
                  status,
                  1L,
                  0L,
                  NOW,
                  NOW));
          return threadId;
        });
  }
}
