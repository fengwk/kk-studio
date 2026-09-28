package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedQueuedCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class HarnessRuntimeJoinAcceptanceTest {
  private static final String HASH = "a".repeat(64);
  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand session(int session, int thread, UUID parent) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewSession(
            TestIds.id(session), TestIds.id(thread), settings(), parent, false),
        List.of(userMessageCommand(TestIds.id(session + 1000), "prompt")));
  }

  private static ThreadJoinRequest request(int invocation, UUID parent, UUID head) {
    return new ThreadJoinRequest(
        TestIds.id(invocation), parent, head, HASH, "assistant", 3, 3, 2, 3);
  }

  @Test
  void rootTicketAcceptsCommandAndReceiptInSameTransactionAndReplays() {
    // 测试意图：one-shot 从首条源命令起存在 durable receipt，重放不重复插入。
    AcceptCommandsCommand source = session(10, 11, null);
    ThreadJoinRequest ticket = request(12, null, null);
    AcceptedCommands first =
        runtime.acceptCommandsAndJoin(source, ticket, AcceptancePreflight.IDENTITY);
    ThreadJoin join = runtime.findJoin(ticket.invocationId()).orElseThrow();
    assertEquals(first.thread().version(), join.afterVersion());
    assertEquals(first.acceptedCommands().getLast().sequence(), join.sourceCommandSequence());
    assertEquals(ThreadLifecycleStatus.ACTIVE, first.thread().status());
    assertFalse(join.matched());
    assertTrue(
        runtime.acceptCommandsAndJoin(source, ticket, AcceptancePreflight.IDENTITY).replayed());
    assertEquals(join, runtime.findJoin(ticket.invocationId()).orElseThrow());
  }

  @Test
  void parentAndNestedChildArePermanentAndJoinFailureRollsBackEverything() {
    // 测试意图：建立不可变三层执行树，preflight 失败时子 session/command/join 一并回滚。
    AcceptedCommands root =
        runtime.acceptCommands(session(20, 21, null), AcceptancePreflight.IDENTITY);
    ThreadJoinRequest firstJoin = request(22, root.thread().id(), root.thread().headEntryId());
    AcceptedCommands first =
        runtime.acceptCommandsAndJoin(
            session(23, 24, root.thread().id()), firstJoin, AcceptancePreflight.IDENTITY);
    ThreadJoinRequest secondJoin = request(25, first.thread().id(), first.thread().headEntryId());
    assertThrows(
        IllegalStateException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(26, 27, first.thread().id()),
                secondJoin,
                (tx, current, commands) -> {
                  throw new IllegalStateException("reject");
                }));
    assertTrue(runtime.findJoin(secondJoin.invocationId()).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(27)).isEmpty()));
    assertEquals(
        List.of(first.thread().id(), root.thread().id()),
        runtime.findAncestorChain(first.thread().id()));
    AcceptedCommands second =
        runtime.acceptCommandsAndJoin(
            session(26, 27, first.thread().id()), secondJoin, AcceptancePreflight.IDENTITY);
    assertEquals(
        List.of(second.thread().id(), first.thread().id(), root.thread().id()),
        runtime.findAncestorChain(second.thread().id()));
  }

  @Test
  void quotaAndInvocationReuseRejectWithoutOrphanSource() {
    // 测试意图：同一父级未匹配额度拒绝第二子；invocation ID 重用不得写入孤儿源 command。
    AcceptedCommands root =
        runtime.acceptCommands(session(30, 31, null), AcceptancePreflight.IDENTITY);
    UUID parent = root.thread().id();
    runtime.acceptCommandsAndJoin(
        session(32, 33, parent),
        request(34, parent, root.thread().headEntryId()),
        AcceptancePreflight.IDENTITY);
    ThreadJoinRequest limited =
        new ThreadJoinRequest(
            TestIds.id(35), parent, root.thread().headEntryId(), HASH, "assistant", 3, 3, 1, 3);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(36, 37, parent), limited, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(37)).isEmpty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(38, 39, parent),
                request(34, parent, root.thread().headEntryId()),
                AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(39)).isEmpty()));
  }

  /**
   * 复刻真实 Stop 的 durable 结果：取消该 Thread 全部排队命令（真实 Stop 会取消它们），再写 STOP 屏障并推进 head。 只有“STOPPED head
   * 且没有排队真实用户输入”的父 Thread 才算暂停交付。
   */
  private UUID seedClosedStopTurn(UUID sessionId, UUID rootEntryId, UUID threadId) {
    return store.transaction(
        tx -> {
          tx.lockTree(threadId);
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          List<ThreadCommand> queued = tx.loadQueuedCommands(threadId);
          if (!queued.isEmpty()) {
            tx.updateCommands(
                queued.stream().map(command -> command.cancel(TestIds.id(9), T0)).toList());
          }
          UUID turnStartId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnStartId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.STOP, settings(), threadId),
                  T0));
          UUID barrierId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  barrierId,
                  sessionId,
                  turnStartId,
                  new AssistantErrorPayload(
                      new AssistantError(AssistantError.CANCELLED_CODE, "Cancelled by user"), null),
                  T0));
          UUID turnEndId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  turnEndId,
                  sessionId,
                  barrierId,
                  new TurnEndPayload(
                      turnStartId,
                      TurnEndOutcome.STOPPED,
                      false,
                      TurnEndReason.USER_STOP,
                      TestIds.id(9)),
                  T0));
          tx.updateThread(thread.advanceHead(turnEndId, T0));
          return turnEndId;
        });
  }

  @Test
  void haltedDeliveryFlushOnNextUserInputAndReplayOrder() {
    // 测试意图：当父线程处于 STOPPED 边界时，子线程空闲匹配的 Join 交付被 hold；
    // 当父线程收到下一批真实验户输入时，原子刷新所有挂起交付（先合成 CUSTOM_MESSAGE 交付命令，再插入用户命令），
    // 并支持 client 以原始期望序号进行幂等重放；若重放跳跃非交付命令或乱序则确定性拒绝。
    AcceptedCommands root =
        runtime.acceptCommands(session(40, 41, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();
    UUID parentHead = root.thread().headEntryId();
    ThreadJoinRequest joinReq = request(42, parentId, parentHead);
    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            session(43, 44, parentId), joinReq, AcceptancePreflight.IDENTITY);
    UUID childId = child.thread().id();

    // 推进父线程 head 至 STOPPED 屏障
    UUID stoppedHead = seedClosedStopTurn(root.session().id(), root.rootEntry().id(), parentId);

    // 停止子线程（使子线程命令取消、闭合并达到 IDLE）：触发 settleStoppedTree
    // 由于父线程处于 STOPPED 边界，子线程的 Join 匹配被成功捕获，但交付被 hold！
    runtime.stop(new StopCommand(childId, TestIds.id(47), child.thread().version()));

    ThreadJoin heldJoin = runtime.findJoin(joinReq.invocationId()).orElseThrow();
    assertTrue(heldJoin.matched());
    assertNull(heldJoin.deliveryCommandSequence());
    int pendingCount = store.transaction(tx -> tx.loadPendingDeliveries(parentId).size());
    assertEquals(1, pendingCount);

    // 父线程当前期望的 next sequence 是 2（因初始命令占据 sequence 1）
    ThreadState parentBeforeInput = store.transaction(tx -> tx.findThread(parentId).orElseThrow());
    long expectedNextSeq = parentBeforeInput.nextCommandSequence();
    assertEquals(2L, expectedNextSeq);

    // 父线程收到真实用户输入：触发 pending delivery flush
    NewThreadCommand userCmd = userMessageCommand(TestIds.id(46), "next prompt");
    AcceptCommandsCommand inputCmd =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(parentId, stoppedHead, expectedNextSeq),
            List.of(userCmd));
    AcceptedCommands accepted = runtime.acceptCommands(inputCmd, AcceptancePreflight.IDENTITY);

    // 验证父线程接收到 delivery (seq 2) + userCmd (seq 3)
    assertEquals(1, accepted.acceptedCommands().size());
    assertEquals(3L, accepted.acceptedCommands().get(0).sequence());

    List<ThreadCommand> parentCommands = store.transaction(tx -> tx.loadCommandsByThread(parentId));
    assertEquals(3, parentCommands.size());
    ThreadCommand delivery = parentCommands.get(1);
    assertEquals(2L, delivery.sequence());
    assertEquals(joinReq.invocationId(), delivery.idempotencyKey());
    assertEquals(ThreadCommandType.CUSTOM_MESSAGE, delivery.type());

    ThreadCommand user = parentCommands.get(2);
    assertEquals(3L, user.sequence());
    assertEquals(TestIds.id(46), user.idempotencyKey());

    // 验证 join 已更新为 delivered
    ThreadJoin deliveredJoin = runtime.findJoin(joinReq.invocationId()).orElseThrow();
    assertEquals(2L, deliveredJoin.deliveryCommandSequence());

    // 验证以 client 原初 expectedNextSeq (2) 进行 exact replay 成功（走通 allPrecedingWereDeliveries 路径）
    AcceptedCommands replayed = runtime.acceptCommands(inputCmd, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(1, replayed.acceptedCommands().size());
    assertEquals(3L, replayed.acceptedCommands().get(0).sequence());

    // 验证若 client 期望的 sequence 大于已持久化命令序号（firstSequence < expected），拒绝重放
    AcceptCommandsCommand invalidSeqReplay =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(parentId, stoppedHead, 10L), List.of(userCmd));
    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.acceptCommands(invalidSeqReplay, AcceptancePreflight.IDENTITY));
  }

  @Test
  void replayPrecedingNonDeliveryRejectionAndNonContiguousRejection() {
    // 测试意图：验证线程批次重放时，若跳过的历史前置序号包含普通用户命令（非 Join 交付）则拒绝重放；
    // 若请求批次内各命令序号不连续同样拒绝。
    AcceptedCommands root =
        runtime.acceptCommands(session(50, 51, null), AcceptancePreflight.IDENTITY);
    UUID threadId = root.thread().id();
    UUID head = root.thread().headEntryId();

    // 连续写入两条用户命令：seq 2 与 seq 3
    NewThreadCommand cmd2 = userMessageCommand(TestIds.id(52), "cmd 2");
    AcceptedCommands acc2 =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(threadId, head, 2L), List.of(cmd2)),
            AcceptancePreflight.IDENTITY);
    assertEquals(2L, acc2.acceptedCommands().get(0).sequence());

    NewThreadCommand cmd3 = userMessageCommand(TestIds.id(53), "cmd 3");
    AcceptedCommands acc3 =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(threadId, head, 3L), List.of(cmd3)),
            AcceptancePreflight.IDENTITY);
    assertEquals(3L, acc3.acceptedCommands().get(0).sequence());

    // 尝试重放 cmd3，但传入 expected = 1L（前置 seq 1、seq 2 不是 delivery），触发 allPrecedingWereDeliveries = false
    // -> 拒绝
    AcceptCommandsCommand nonDeliveryPreceding =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(threadId, head, 1L), List.of(cmd3));
    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.acceptCommands(nonDeliveryPreceding, AcceptancePreflight.IDENTITY));

    // 尝试重放不连续的命令批次：[cmd2, cmd4]
    NewThreadCommand cmd4 = userMessageCommand(TestIds.id(54), "cmd 4");
    AcceptedCommands acc4 =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(threadId, head, 4L), List.of(cmd4)),
            AcceptancePreflight.IDENTITY);
    assertEquals(4L, acc4.acceptedCommands().get(0).sequence());

    AcceptCommandsCommand nonContiguousBatch =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(threadId, head, 2L), List.of(cmd2, cmd4));
    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.acceptCommands(nonContiguousBatch, AcceptancePreflight.IDENTITY));
  }

  @Test
  void ancestorIdleTransitionToWaitingChildrenOnChildSessionAndThreadCommands() {
    // 测试意图：验证父级/祖先线程处于 IDLE 状态时，接纳新子会话（acceptNewSession）或已有子线程接纳新命令（acceptOnThread）均将 IDLE 祖先原子推进为
    // WAITING_CHILDREN。
    AcceptedCommands parent =
        runtime.acceptCommands(session(60, 61, null), AcceptancePreflight.IDENTITY);
    UUID parentId = parent.thread().id();

    // 将 parent 置为 IDLE
    store.transaction(
        tx -> {
          ThreadState p = tx.lockThread(parentId).orElseThrow();
          tx.updateThread(p.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T0));
          return null;
        });
    assertEquals(
        ThreadLifecycleStatus.IDLE,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().status()));

    // 1. 创建子会话：触发 lockedAncestors 遍历，将 IDLE parent 推进为 WAITING_CHILDREN
    ThreadJoinRequest join1 = request(62, parentId, parent.thread().headEntryId());
    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            session(63, 64, parentId), join1, AcceptancePreflight.IDENTITY);
    UUID childId = child.thread().id();

    assertEquals(
        ThreadLifecycleStatus.WAITING_CHILDREN,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().status()));

    // 再次将 parent 置为 IDLE
    store.transaction(
        tx -> {
          ThreadState p = tx.lockThread(parentId).orElseThrow();
          tx.updateThread(p.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T0));
          return null;
        });
    assertEquals(
        ThreadLifecycleStatus.IDLE,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().status()));

    // 2. 在 child 上接纳新命令：触发 locked.chain 遍历，将 IDLE parent 推进为 WAITING_CHILDREN
    ThreadState childCurrent = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    NewThreadCommand childCmd = userMessageCommand(TestIds.id(65), "child progress");
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                childId, childCurrent.headEntryId(), childCurrent.nextCommandSequence()),
            List.of(childCmd)),
        AcceptancePreflight.IDENTITY);

    assertEquals(
        ThreadLifecycleStatus.WAITING_CHILDREN,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().status()));
  }

  @Test
  void quotaExistingBusyChildAndHierarchyQuotas() {
    // 测试意图：验证当已有子线程处于 ACTIVE 忙碌状态时，向该子线程关联 Join 不视为新增活跃子线程，不受 maxConcurrentChildren 额度阻断；
    // 而当子线程为空闲时超额接纳则确定性拒绝；同时校验深度配额与整树活跃线程配额。
    AcceptedCommands parent =
        runtime.acceptCommands(session(70, 71, null), AcceptancePreflight.IDENTITY);
    UUID parentId = parent.thread().id();
    UUID parentHead = parent.thread().headEntryId();

    // 创建子线程 child1，限额 maxConcurrentChildren = 1
    ThreadJoinRequest join1 =
        new ThreadJoinRequest(TestIds.id(72), parentId, parentHead, HASH, "assistant", 3, 3, 1, 3);
    AcceptedCommands child1 =
        runtime.acceptCommandsAndJoin(
            session(73, 74, parentId), join1, AcceptancePreflight.IDENTITY);
    UUID child1Id = child1.thread().id();

    // 此时 child1 是 ACTIVE 状态，parent 已有 1 个活跃孩子（已达配额 1）
    int activeCount = store.transaction(tx -> tx.countActiveChildren(parentId));
    assertEquals(1, activeCount);

    // 向已处于 ACTIVE 的 child1 线程发送新命令并附带 Join（非创建路径且已有活跃线程）：
    // newActiveChild = false，因此不触发配额超出异常！
    ThreadState c1Current = store.transaction(tx -> tx.findThread(child1Id).orElseThrow());
    ThreadJoinRequest join1Second =
        new ThreadJoinRequest(TestIds.id(75), parentId, parentHead, HASH, "assistant", 3, 3, 1, 3);
    NewThreadCommand cmd = userMessageCommand(TestIds.id(76), "c1 continue");
    AcceptedCommands accBusy =
        runtime.acceptCommandsAndJoin(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(
                    child1Id, c1Current.headEntryId(), c1Current.nextCommandSequence()),
                List.of(cmd)),
            join1Second,
            AcceptancePreflight.IDENTITY);
    assertFalse(accBusy.replayed());

    // 将 child1 置为 IDLE
    store.transaction(
        tx -> {
          ThreadState c = tx.lockThread(child1Id).orElseThrow();
          tx.updateThread(c.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T0));
          return null;
        });

    // 创建 child2，占满活跃孩子配额（active = 1）
    ThreadJoinRequest join2 =
        new ThreadJoinRequest(TestIds.id(77), parentId, parentHead, HASH, "assistant", 3, 3, 1, 3);
    runtime.acceptCommandsAndJoin(session(78, 79, parentId), join2, AcceptancePreflight.IDENTITY);

    // 此时 child1 是 IDLE，如果向 child1 接纳带 Join 命令，newActiveChild = true，因配额超限被拒绝
    ThreadState c1Idle = store.transaction(tx -> tx.findThread(child1Id).orElseThrow());
    ThreadJoinRequest join1Third =
        new ThreadJoinRequest(TestIds.id(80), parentId, parentHead, HASH, "assistant", 3, 3, 1, 3);
    NewThreadCommand cmdIdle = userMessageCommand(TestIds.id(81), "c1 idle try");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(
                        child1Id, c1Idle.headEntryId(), c1Idle.nextCommandSequence()),
                    List.of(cmdIdle)),
                join1Third,
                AcceptancePreflight.IDENTITY));

    // 校验全局并发额度超限（maxConcurrentThreads = 1，而当前全局活跃子线程已有 child2 = 1）
    ThreadJoinRequest lowGlobalQuota =
        new ThreadJoinRequest(TestIds.id(82), parentId, parentHead, HASH, "assistant", 3, 5, 5, 1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(83, 84, parentId), lowGlobalQuota, AcceptancePreflight.IDENTITY));

    // 校验深度配额超限（maxDepth = 1，而新子线程深度为 2）
    ThreadJoinRequest lowDepthQuota =
        new ThreadJoinRequest(TestIds.id(85), parentId, parentHead, HASH, "assistant", 3, 1, 5, 5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(86, 87, parentId), lowDepthQuota, AcceptancePreflight.IDENTITY));
  }

  @Test
  void globalSubagentConcurrencyCapSpansRootTreesAndReleasesOnIdle() {
    // 测试意图：task 的 maxConcurrentThreads 是全局上限——统计所有 root 下的活跃执行子 Thread（不含 root 自身）。
    // 另一个 root 下的活跃子线程同样占用额度（旧的按树统计会错误放行）；达到上限后新子线程被拒绝且无残留，
    // exact replay 不重复占额度，忙子 resume 不新增额度仍允许，子线程回到 IDLE 后额度即释放。
    AcceptedCommands rootA =
        runtime.acceptCommands(session(2200, 2201, null), AcceptancePreflight.IDENTITY);
    AcceptedCommands rootB =
        runtime.acceptCommands(session(2210, 2211, null), AcceptancePreflight.IDENTITY);
    UUID parentA = rootA.thread().id();
    UUID parentB = rootB.thread().id();
    UUID headA = rootA.thread().headEntryId();
    UUID headB = rootB.thread().headEntryId();

    // cap = 2：A 下的 childA1 与 B 下的 childB1 各占 1 个额度
    ThreadJoinRequest a1 =
        new ThreadJoinRequest(TestIds.id(2202), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    AcceptedCommands childA1 =
        runtime.acceptCommandsAndJoin(
            session(2203, 2204, parentA), a1, AcceptancePreflight.IDENTITY);
    ThreadJoinRequest b1 =
        new ThreadJoinRequest(TestIds.id(2212), parentB, headB, HASH, "assistant", 3, 3, 5, 2);
    AcceptedCommands childB1 =
        runtime.acceptCommandsAndJoin(
            session(2213, 2214, parentB), b1, AcceptancePreflight.IDENTITY);
    assertEquals(2, activeSubagentThreads());

    // 全局额度已满：A 树自己只有 1 个活跃子线程，但 B 树的 childB1 占用全局额度 -> 拒绝（修复点）
    ThreadJoinRequest a2 =
        new ThreadJoinRequest(TestIds.id(2205), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(2206, 2207, parentA), a2, AcceptancePreflight.IDENTITY));
    assertTrue(store.transaction(tx -> tx.findThread(TestIds.id(2207))).isEmpty());
    assertTrue(store.transaction(tx -> tx.findJoin(TestIds.id(2205))).isEmpty());

    // exact replay 命中既有 join，不重复占额度
    AcceptedCommands replayed =
        runtime.acceptCommandsAndJoin(
            session(2203, 2204, parentA), a1, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(2, activeSubagentThreads());

    // 忙子 resume 不新增额度，即使全局额度已满仍允许
    ThreadState childB1State =
        store.transaction(tx -> tx.findThread(childB1.thread().id()).orElseThrow());
    ThreadJoinRequest b1Resume =
        new ThreadJoinRequest(TestIds.id(2215), parentB, headB, HASH, "assistant", 3, 3, 5, 2);
    AcceptedCommands resumed =
        runtime.acceptCommandsAndJoin(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(
                    childB1.thread().id(),
                    childB1State.headEntryId(),
                    childB1State.nextCommandSequence()),
                List.of(userMessageCommand(TestIds.id(2216), "resume busy child"))),
            b1Resume,
            AcceptancePreflight.IDENTITY);
    assertFalse(resumed.replayed());
    assertEquals(2, activeSubagentThreads());

    // childA1 回到 IDLE：额度释放，新子线程可再次占位
    store.transaction(
        tx -> {
          ThreadState child = tx.lockThread(childA1.thread().id()).orElseThrow();
          tx.updateThread(child.changeLifecycleStatus(ThreadLifecycleStatus.IDLE, T5));
          return null;
        });
    assertEquals(1, activeSubagentThreads());
    ThreadJoinRequest a3 =
        new ThreadJoinRequest(TestIds.id(2208), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    runtime.acceptCommandsAndJoin(session(2209, 2220, parentA), a3, AcceptancePreflight.IDENTITY);
    assertEquals(2, activeSubagentThreads());
  }

  /** 全局活跃执行子 Thread 数（跨 root、不含 root 自身）。 */
  private int activeSubagentThreads() {
    return store.transaction(tx -> tx.countActiveSubagentThreads());
  }

  @Test
  void joinAcceptanceBoundaryRejections() {
    // 测试意图：验证 Join 接纳边界校验：创建子线程必须附带原子 Join、join parent 不匹配、
    // 父线程处于 STOPPED 边界拒绝接纳、父线程 headEntryId 不匹配、重放命令缺失 Join 以及 Join 凭据重用冲突。
    AcceptedCommands root =
        runtime.acceptCommands(session(90, 91, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();
    UUID parentHead = root.thread().headEntryId();

    // 1. 创建子会话指定 parentId 但未提供 join -> 拒绝
    assertThrows(
        IllegalArgumentException.class,
        () -> runtime.acceptCommands(session(92, 93, parentId), AcceptancePreflight.IDENTITY));

    // 2. join parent 与 target.parentThreadId 不一致 -> 拒绝
    ThreadJoinRequest diffParent = request(94, TestIds.id(999), parentHead);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(95, 96, parentId), diffParent, AcceptancePreflight.IDENTITY));

    // 3. parent headEntryId 不匹配 -> 拒绝
    ThreadJoinRequest wrongHead = request(97, parentId, TestIds.id(888));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(98, 99, parentId), wrongHead, AcceptancePreflight.IDENTITY));

    // 4. parent 在 STOPPED 边界 -> 拒绝
    UUID stoppedHead = seedClosedStopTurn(root.session().id(), root.rootEntry().id(), parentId);
    ThreadJoinRequest stoppedParentJoin = request(100, parentId, stoppedHead);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(101, 102, parentId), stoppedParentJoin, AcceptancePreflight.IDENTITY));

    // 5. 不存在 parentId -> 拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(103, 104, TestIds.id(777)),
                request(105, TestIds.id(777), stoppedHead),
                AcceptancePreflight.IDENTITY));

    // 6. 源命令重放时缺失 Join：在普通线程先以无 join 接受命令，随后再尝试带 join 重放相同命令 -> 拒绝
    AcceptedCommands normal =
        runtime.acceptCommands(session(106, 107, null), AcceptancePreflight.IDENTITY);
    NewThreadCommand cmdAlone = userMessageCommand(TestIds.id(108), "alone");
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                normal.thread().id(),
                normal.thread().headEntryId(),
                normal.thread().nextCommandSequence()),
            List.of(cmdAlone)),
        AcceptancePreflight.IDENTITY);

    ThreadJoinRequest newJoinOnReplayed = request(109, null, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(
                        normal.thread().id(), normal.thread().headEntryId(), 2L),
                    List.of(cmdAlone)),
                newJoinOnReplayed,
                AcceptancePreflight.IDENTITY));

    // 7. Join invocation identity 被以不同参数（如不同 agent）重用 -> 拒绝
    ThreadJoinRequest initialJoin = request(110, null, null);
    runtime.acceptCommandsAndJoin(
        session(111, 112, null), initialJoin, AcceptancePreflight.IDENTITY);

    ThreadJoinRequest conflictedJoin =
        new ThreadJoinRequest(TestIds.id(110), null, null, HASH, "different-agent", 3, 3, 2, 3);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(113, 114, null), conflictedJoin, AcceptancePreflight.IDENTITY));
  }

  @Test
  void goalAndCustomMessageInputRecognition() {
    // 测试意图：验证 typed GOAL 命令与非 SystemReminder 的 CUSTOM_MESSAGE 正确被识别为真实验户输入，
    // 并支持从 Goal 正文派生初始会话名称。
    NewThreadCommand goalCmd =
        new NewThreadCommand(
            new GoalCommandPayload("custom goal objective text"), TestIds.id(120), HASH);
    AcceptedCommands goalAccepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(
                    TestIds.id(121), TestIds.id(122), settings(), null, false),
                List.of(goalCmd)),
            AcceptancePreflight.IDENTITY);
    assertEquals("custom goal objective text", goalAccepted.session().name());

    // 非 SystemReminder 的 CUSTOM_MESSAGE
    NewThreadCommand customMsgCmd =
        new NewThreadCommand(
            new CustomMessageCommandPayload(AgentMessage.user("normal custom")),
            TestIds.id(123),
            HASH);
    AcceptedCommands customAccepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(
                    TestIds.id(124), TestIds.id(125), settings(), null, false),
                List.of(customMsgCmd)),
            AcceptancePreflight.IDENTITY);
    assertNotNull(customAccepted);
  }

  @Test
  void stoppedParentResumesOnlyOnGenuineUserInput() {
    // 测试意图：STOPPED 边界的父线程只有在排队真实用户输入（USER / GOAL / 非 reminder CUSTOM_MESSAGE）时才解除
    // 暂停并接受新 join；仅排队运行时 reminder 或 SET_* 等非用户 command 时保持暂停，join 被确定性拒绝。
    // 每个 payload 用独立父线程隔离，避免“首个 genuine 命令即返回”的早退互相掩盖。
    assertStoppedParentAcceptsJoinAfterQueueing(
        2000, 2001, 2003, 2004, 2005, 2006, userMessagePayload("resume"), true);
    assertStoppedParentAcceptsJoinAfterQueueing(
        2010, 2011, 2013, 2014, 2015, 2016, new GoalCommandPayload("resume goal"), true);
    assertStoppedParentAcceptsJoinAfterQueueing(
        2020,
        2021,
        2023,
        2024,
        2025,
        2026,
        new CustomMessageCommandPayload(AgentMessage.user("plain custom")),
        true);
    assertStoppedParentAcceptsJoinAfterQueueing(
        2030,
        2031,
        2033,
        2034,
        2035,
        2036,
        new CustomMessageCommandPayload(SystemReminder.message("soft budget nudge")),
        false);
    assertStoppedParentAcceptsJoinAfterQueueing(
        2040,
        2041,
        2043,
        2044,
        2045,
        2046,
        new SetModelCommandPayload(new ModelSelection("provider", "model", "v1")),
        false);
  }

  /**
   * 在 STOPPED 边界的父线程上预先排队给定 payload，再尝试为该父线程建立子 Session + Join。
   *
   * <p>命令直接写入 store（绕过 acceptCommands 的 batch 形态约束）：join 接受路径只读取父线程 mailbox 判定暂停语义， 不触碰父线程的
   * nextCommandSequence。
   */
  private void assertStoppedParentAcceptsJoinAfterQueueing(
      int parentSession,
      int parentThread,
      int queuedCommandId,
      int childSession,
      int childThread,
      int invocationId,
      ThreadCommandPayload queuedPayload,
      boolean resumes) {
    AcceptedCommands root =
        runtime.acceptCommands(
            session(parentSession, parentThread, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();
    UUID stoppedHead = seedClosedStopTurn(root.session().id(), root.rootEntry().id(), parentId);
    seedQueuedCommand(store, parentId, 2L, queuedPayload, TestIds.id(queuedCommandId));

    ThreadJoinRequest join = request(invocationId, parentId, stoppedHead);
    AcceptCommandsCommand child = session(childSession, childThread, parentId);
    if (resumes) {
      AcceptedCommands accepted =
          runtime.acceptCommandsAndJoin(child, join, AcceptancePreflight.IDENTITY);
      assertFalse(accepted.replayed());
      assertEquals(parentId, runtime.findJoin(join.invocationId()).orElseThrow().parentThreadId());
    } else {
      assertThrows(
          IllegalArgumentException.class,
          () -> runtime.acceptCommandsAndJoin(child, join, AcceptancePreflight.IDENTITY));
      assertTrue(runtime.findJoin(join.invocationId()).isEmpty());
    }
  }

  @Test
  void replayOfExistingChildSessionWithLowerUuidsLocksInAscendingOrder() {
    // 测试意图：重放已存在的子 Session（子 Session/Thread 的 UUID 都小于父）时，接受控制面必须在树锁内复读子线程并
    // 按 UUID 升序补锁；InMemory store 的严格升序 Thread 锁序会直接拒绝“先锁父、再回补低 UUID 子”的逆序补锁。
    AcceptedCommands root =
        runtime.acceptCommands(session(2100, 2101, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();
    ThreadJoinRequest join = request(2102, parentId, root.thread().headEntryId());
    AcceptCommandsCommand child = session(1, 2, parentId);

    AcceptedCommands created =
        runtime.acceptCommandsAndJoin(child, join, AcceptancePreflight.IDENTITY);
    assertFalse(created.replayed());
    assertTrue(TestIds.id(1).equals(created.session().id()));
    assertTrue(TestIds.id(2).equals(created.thread().id()));

    AcceptedCommands replayed =
        runtime.acceptCommandsAndJoin(child, join, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(created.thread().id(), replayed.thread().id());
    assertEquals(1, store.transaction(tx -> tx.listChildren(parentId)).size());
  }

  @Test
  void replayInitialAndAcceptNewThreadValidation() {
    // 测试意图：验证 acceptNewThread 起始 Entry 不存在或落在未闭合 STOP Turn 内时确定性拒绝，
    // 以及 replayInitial 中 idempotencyKey 篡改或请求哈希不匹配的冲突拒绝。
    AcceptedCommands root =
        runtime.acceptCommands(session(130, 131, null), AcceptancePreflight.IDENTITY);
    UUID sessionId = root.session().id();

    // 1. acceptNewThread 指定不存在的 startEntryId -> HarnessRuntimeNotFoundException
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () ->
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewThread(
                        sessionId, TestIds.id(999), TestIds.id(132), false),
                    List.of(userMessageCommand(TestIds.id(133), "fork"))),
                AcceptancePreflight.IDENTITY));

    // 2. replayInitial idempotencyKey 重用但 requestHash 不一致 -> IDEMPOTENCY_KEY_REUSED
    NewThreadCommand initCmd = userMessageCommand(TestIds.id(134), "initial message");
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(
                TestIds.id(135), TestIds.id(136), settings(), null, false),
            List.of(initCmd)),
        AcceptancePreflight.IDENTITY);

    NewThreadCommand tamperedHashCmd =
        new NewThreadCommand(initCmd.payload(), TestIds.id(134), "b".repeat(64));
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewSession(
                        TestIds.id(135), TestIds.id(136), settings(), null, false),
                    List.of(tamperedHashCmd)),
                AcceptancePreflight.IDENTITY));

    // 3. replayInitial 不同的 creationRequestHash (不同 sessionId) -> THREAD_ID_REUSED
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewSession(
                        TestIds.id(999), TestIds.id(136), settings(), null, false),
                    List.of(initCmd)),
                AcceptancePreflight.IDENTITY));
  }
}
