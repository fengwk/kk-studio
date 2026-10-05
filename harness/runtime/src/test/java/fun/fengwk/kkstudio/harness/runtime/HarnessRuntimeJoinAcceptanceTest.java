package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
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

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
    assertEquals(first.acceptedCommands().getLast().sequence(), join.sourceCommandSequence());
    assertEquals(ThreadExecutionControl.RUNNABLE, first.thread().executionControl());
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

  @Test
  void stoppedParentMaterializesChildResultWithoutQueueingDelivery() {
    // 测试意图：真实 Stop 把父线程（连同子树）置为 STOPPED 后，子线程在执行终止边界冻结的 Join 结果不再等待父线程的下一次用户输入，
    // 而是把 NOTIFICATION 直接物化进父线程历史：父线程既没有排队交付命令，也不会被请求 THREAD Work。
    AcceptedCommands root =
        runtime.acceptCommands(session(40, 41, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();
    ThreadJoinRequest joinReq = request(42, parentId, root.thread().headEntryId());
    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            session(43, 44, parentId), joinReq, AcceptancePreflight.IDENTITY);
    UUID childId = child.thread().id();

    // 真实 Stop 才使目标与完整子树 STOPPED（仅推进 head 的 seed 不再代表暂停语义）。
    long parentVersion = store.transaction(tx -> tx.findThread(parentId).orElseThrow().version());
    StopResult stopResult = runtime.stop(new StopCommand(parentId, TestIds.id(45), parentVersion));
    assertEquals(ThreadExecutionControl.STOPPED, stopResult.thread().executionControl());
    assertEquals(
        ThreadExecutionControl.STOPPED,
        store.transaction(tx -> tx.findThread(childId).orElseThrow().executionControl()));

    // 子 Join 在停止边界冻结并已写入交付引用，不再滞留为 pending delivery。
    ThreadJoin settled = runtime.findJoin(joinReq.invocationId()).orElseThrow();
    assertTrue(settled.matched());
    assertNotNull(settled.deliveryCommandSequence());
    assertTrue(store.transaction(tx -> tx.loadPendingDeliveries(parentId)).isEmpty());

    // 通知直接物化到父线程历史；不留下 queued 命令，也不请求父 Thread Work。
    assertTrue(parentHistoryContainsNotification(parentId));
    assertTrue(
        store.<Boolean>transaction(
            tx -> {
              tx.lockThread(parentId);
              return tx.loadQueuedCommands(parentId).isEmpty();
            }));
    assertTrue(
        store
            .transaction(tx -> tx.findWork(new WorkTarget(WorkTargetType.THREAD, parentId)))
            .isEmpty());
  }

  @Test
  void deliveryCommandPrecedingReplayOrderAccepted() {
    // 测试意图：父线程仍 RUNNABLE 时，子线程终止把 NOTIFICATION 交付命令排在父线程 seq 2；
    // 之后重放父线程用户命令时，只有被跳过的前置序号全部是 Join 交付命令才接受重放，其余乱序确定性拒绝。
    AcceptedCommands root =
        runtime.acceptCommands(session(46, 47, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();
    ThreadJoinRequest joinReq = request(48, parentId, root.thread().headEntryId());
    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            session(49, 50, parentId), joinReq, AcceptancePreflight.IDENTITY);
    UUID childId = child.thread().id();

    // 父线程 RUNNABLE：子线程终止把交付命令排到父线程 seq 2，并请求父 Thread Work。
    long childVersion = store.transaction(tx -> tx.findThread(childId).orElseThrow().version());
    runtime.stop(new StopCommand(childId, TestIds.id(51), childVersion));

    ThreadJoin deliveredJoin = runtime.findJoin(joinReq.invocationId()).orElseThrow();
    assertTrue(deliveredJoin.matched());
    assertEquals(2L, deliveredJoin.deliveryCommandSequence());
    ThreadState parent = store.transaction(tx -> tx.findThread(parentId).orElseThrow());
    assertEquals(ThreadExecutionControl.RUNNABLE, parent.executionControl());
    assertEquals(3L, parent.nextCommandSequence());

    // 父线程接纳新的真实用户输入，落在 seq 3。
    NewThreadCommand userCmd = userMessageCommand(TestIds.id(52), "next prompt");
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(parentId, parent.headEntryId(), 3L),
                List.of(userCmd)),
            AcceptancePreflight.IDENTITY);
    assertEquals(1, accepted.acceptedCommands().size());
    assertEquals(3L, accepted.acceptedCommands().get(0).sequence());

    // client 仍以原始期望序号 2 重放：被跳过的 seq 2 恰是 Join 交付命令，按契约应接受重放。
    AcceptCommandsCommand originalReplay =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(parentId, parent.headEntryId(), 2L), List.of(userCmd));
    AcceptedCommands replayed =
        runtime.acceptCommands(originalReplay, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(3L, replayed.acceptedCommands().get(0).sequence());

    // 期望序号大于已持久化命令序号（firstSequence < expected）不是合法重放跳跃，确定性拒绝。
    AcceptCommandsCommand invalidSeqReplay =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(parentId, parent.headEntryId(), 10L), List.of(userCmd));
    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.acceptCommands(invalidSeqReplay, AcceptancePreflight.IDENTITY));
  }

  /** 该 Thread 当前 head 所在历史路径上是否已物化系统通知（STOPPED 父线程的 Join 交付直接固化到历史）。 */
  private boolean parentHistoryContainsNotification(UUID threadId) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.findThread(threadId).orElseThrow();
          EntryPath path = tx.loadEntryPath(thread.headEntryId());
          for (Entry entry : path.entries()) {
            if (entry.payload() instanceof NotificationPayload) {
              return true;
            }
          }
          return false;
        });
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
  void parentExecutionControlUnchangedByChildSessionAndThreadCommands() {
    // 测试意图：执行控制只有 RUNNABLE / STOPPED 两态，不再有 IDLE / WAITING_CHILDREN 递归生命周期：
    // 接纳新子会话（acceptNewSession）或在既有子线程上接纳新命令（acceptOnThread）都不会改写父/祖先线程的执行控制。
    AcceptedCommands parent =
        runtime.acceptCommands(session(60, 61, null), AcceptancePreflight.IDENTITY);
    UUID parentId = parent.thread().id();
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));

    // 1. 创建子会话：父线程执行控制保持 RUNNABLE，子线程正确挂在父线程下。
    ThreadJoinRequest join1 = request(62, parentId, parent.thread().headEntryId());
    AcceptedCommands child =
        runtime.acceptCommandsAndJoin(
            session(63, 64, parentId), join1, AcceptancePreflight.IDENTITY);
    UUID childId = child.thread().id();
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));
    assertEquals(
        parentId, store.transaction(tx -> tx.findThread(childId).orElseThrow().parentThreadId()));

    // 2. 在既有子线程上接纳新命令：父子执行控制均保持 RUNNABLE。
    ThreadState childCurrent = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    NewThreadCommand childCmd = userMessageCommand(TestIds.id(65), "child progress");
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                childId, childCurrent.headEntryId(), childCurrent.nextCommandSequence()),
            List.of(childCmd)),
        AcceptancePreflight.IDENTITY);

    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(childId).orElseThrow().executionControl()));
  }

  @Test
  void quotaCountsIncompleteChildJoinsAndDepthQuota() {
    // 测试意图：子任务额度按“尚未冻结结果的 Join”（terminalEntryId == null）计数：达到 maxConcurrentChildren 时第二个子任务
    // 确定性拒绝且无残留；子线程终止冻结其 Join 后额度立即释放，第二个子任务才被接受；新子线程深度超过 maxDepth 同样拒绝。
    AcceptedCommands parent =
        runtime.acceptCommands(session(70, 71, null), AcceptancePreflight.IDENTITY);
    UUID parentId = parent.thread().id();
    UUID parentHead = parent.thread().headEntryId();

    // child1 占用 parent 的唯一子任务额度（maxConcurrentChildren = 1）。
    ThreadJoinRequest join1 =
        new ThreadJoinRequest(TestIds.id(72), parentId, parentHead, HASH, "assistant", 3, 3, 1, 5);
    AcceptedCommands child1 =
        runtime.acceptCommandsAndJoin(
            session(73, 74, parentId), join1, AcceptancePreflight.IDENTITY);
    UUID child1Id = child1.thread().id();
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));

    // 第二个子任务：未完成 Join 计数已达上限 -> 拒绝，且不留下 Thread / Join 残留。
    ThreadJoinRequest join2 =
        new ThreadJoinRequest(TestIds.id(75), parentId, parentHead, HASH, "assistant", 3, 3, 1, 5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(76, 77, parentId), join2, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(77)).isEmpty()));
    assertTrue(runtime.findJoin(join2.invocationId()).isEmpty());

    // 停止 child1：Join 在执行终止边界冻结为已完成，parent 子任务额度随之释放。
    long child1Version = store.transaction(tx -> tx.findThread(child1Id).orElseThrow().version());
    runtime.stop(new StopCommand(child1Id, TestIds.id(78), child1Version));
    assertTrue(runtime.findJoin(join1.invocationId()).orElseThrow().matched());
    assertEquals(0, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));

    // 额度释放后，第二个子任务被接受。
    AcceptedCommands child2 =
        runtime.acceptCommandsAndJoin(
            session(76, 77, parentId), join2, AcceptancePreflight.IDENTITY);
    assertFalse(child2.replayed());
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));

    // 深度配额：新子线程深度为 2，超过 maxDepth = 1 -> 拒绝（与子任务额度无关）。
    ThreadJoinRequest tooDeep =
        new ThreadJoinRequest(TestIds.id(79), parentId, parentHead, HASH, "assistant", 3, 1, 5, 5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(80, 81, parentId), tooDeep, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(81)).isEmpty()));
    assertTrue(runtime.findJoin(tooDeep.invocationId()).isEmpty());
  }

  @Test
  void globalSubagentConcurrencyCapReleasesOnJoinTermination() {
    // 测试意图：task 的 maxConcurrentThreads 是全局上限——统计所有 root 下尚未冻结结果的执行子 Join（不含 root ticket）。
    // 另一个 root 下的子任务同样占用额度；达到上限后新子任务被拒绝且无残留；exact replay 不重复占额度；
    // root ticket（parentThreadId 为空）不计入全局额度；某个子线程终止冻结其 Join 后额度立即释放。
    AcceptedCommands rootA =
        runtime.acceptCommands(session(2200, 2201, null), AcceptancePreflight.IDENTITY);
    AcceptedCommands rootB =
        runtime.acceptCommands(session(2210, 2211, null), AcceptancePreflight.IDENTITY);
    UUID parentA = rootA.thread().id();
    UUID parentB = rootB.thread().id();
    UUID headA = rootA.thread().headEntryId();
    UUID headB = rootB.thread().headEntryId();

    // cap = 2：A 下的 childA1 与 B 下的 childB1 各占 1 个额度。
    ThreadJoinRequest a1 =
        new ThreadJoinRequest(TestIds.id(2202), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    AcceptedCommands childA1 =
        runtime.acceptCommandsAndJoin(
            session(2203, 2204, parentA), a1, AcceptancePreflight.IDENTITY);
    ThreadJoinRequest b1 =
        new ThreadJoinRequest(TestIds.id(2212), parentB, headB, HASH, "assistant", 3, 3, 5, 2);
    runtime.acceptCommandsAndJoin(session(2213, 2214, parentB), b1, AcceptancePreflight.IDENTITY);
    assertEquals(2, activeSubagentJoinCount());

    // root ticket（parentThreadId 为空）不占全局 subagent 额度：额度已满时仍可创建。
    AcceptedCommands rootTicket =
        runtime.acceptCommandsAndJoin(
            session(2217, 2218, null), request(2219, null, null), AcceptancePreflight.IDENTITY);
    assertFalse(rootTicket.replayed());
    assertNull(runtime.findJoin(TestIds.id(2219)).orElseThrow().parentThreadId());
    assertEquals(2, activeSubagentJoinCount());

    // 全局额度已满：A 树自己只有 1 个子任务，但 B 树的 childB1 占用全局额度 -> 拒绝，且无残留。
    ThreadJoinRequest a2 =
        new ThreadJoinRequest(TestIds.id(2205), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(2206, 2207, parentA), a2, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(2207)).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findJoin(TestIds.id(2205)).isEmpty()));

    // exact replay 命中既有 join，不重复占额度。
    AcceptedCommands replayed =
        runtime.acceptCommandsAndJoin(
            session(2203, 2204, parentA), a1, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(2, activeSubagentJoinCount());

    // 停止 childA1：Join 冻结为已完成 -> 全局额度释放一个。
    long childA1Version =
        store.transaction(tx -> tx.findThread(childA1.thread().id()).orElseThrow().version());
    runtime.stop(new StopCommand(childA1.thread().id(), TestIds.id(2215), childA1Version));
    assertTrue(runtime.findJoin(a1.invocationId()).orElseThrow().matched());
    assertEquals(1, activeSubagentJoinCount());

    // 额度释放后，A 下的新子任务再次被接受。
    ThreadJoinRequest a3 =
        new ThreadJoinRequest(TestIds.id(2208), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    AcceptedCommands childA3 =
        runtime.acceptCommandsAndJoin(
            session(2209, 2220, parentA), a3, AcceptancePreflight.IDENTITY);
    assertFalse(childA3.replayed());
    assertEquals(2, activeSubagentJoinCount());
  }

  /** 全局未完成的执行子 Join 数（跨 root、不含 parentThreadId 为空的 root ticket）。 */
  private int activeSubagentJoinCount() {
    return store.transaction(tx -> tx.countIncompleteSubagentJoins());
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

    // 4. parent 被真实 Stop 置为 STOPPED -> 拒绝新子任务（STOPPED 是持久条件，不再依赖 head 形态）
    long parentVersion = store.transaction(tx -> tx.findThread(parentId).orElseThrow().version());
    runtime.stop(new StopCommand(parentId, TestIds.id(100), parentVersion));
    UUID stoppedHead = store.transaction(tx -> tx.findThread(parentId).orElseThrow().headEntryId());
    assertEquals(
        ThreadExecutionControl.STOPPED,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));
    ThreadJoinRequest stoppedParentJoin = request(101, parentId, stoppedHead);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(102, 103, parentId), stoppedParentJoin, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(103)).isEmpty()));
    assertTrue(runtime.findJoin(stoppedParentJoin.invocationId()).isEmpty());

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
  void stoppedParentResumesOnTrustedTaskInput() {
    // 测试意图：STOPPED 父线程接纳可信任务输入（USER / GOAL / CUSTOM_MESSAGE）时在同一事务内恢复 RUNNABLE；恢复只作用于
    // 目标线程，随 Stop 一起暂停的后代保持 STOPPED。每个子用例使用全新 store/runtime，避免全局 subagent 额度跨用例累积。
    assertStoppedParentResumeOutcome(3000, List.of(userMessagePayload("resume")), true);
    assertStoppedParentResumeOutcome(3010, List.of(new GoalCommandPayload("resume goal")), true);
    assertStoppedParentResumeOutcome(
        3020, List.of(new CustomMessageCommandPayload(AgentMessage.user("plain custom"))), true);
    // 所有 CUSTOM 都是可信输入，不再按 <system-reminder> 文本标签区分特权来源；系统通知只经 NOTIFICATION Command。
    assertStoppedParentResumeOutcome(
        3030,
        List.of(new CustomMessageCommandPayload(SystemReminder.message("soft budget nudge"))),
        true);
  }

  /**
   * 在独立 store/runtime 上构造“父线程 + 已随父 Stop 一起暂停的子线程”，再向 STOPPED 父线程接纳给定 command batch： 断言父线程是否恢复为
   * RUNNABLE，以及被一起暂停的后代是否保持 STOPPED。
   */
  private void assertStoppedParentResumeOutcome(
      int base, List<ThreadCommandPayload> payloads, boolean expectResumed) {
    InMemoryHarnessStore caseStore = new InMemoryHarnessStore();
    HarnessRuntime caseRuntime =
        HarnessRuntimeTestSupport.runtime(caseStore, Clock.fixed(T0, ZoneOffset.UTC));
    AcceptedCommands root =
        caseRuntime.acceptCommands(session(base, base + 1, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();
    ThreadJoinRequest join = request(base + 2, parentId, root.thread().headEntryId());
    AcceptedCommands child =
        caseRuntime.acceptCommandsAndJoin(
            session(base + 3, base + 4, parentId), join, AcceptancePreflight.IDENTITY);
    UUID childId = child.thread().id();

    long parentVersion =
        caseStore.transaction(tx -> tx.findThread(parentId).orElseThrow().version());
    caseRuntime.stop(new StopCommand(parentId, TestIds.id(base + 5), parentVersion));
    assertEquals(
        ThreadExecutionControl.STOPPED,
        caseStore.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));

    ThreadState stopped = caseStore.transaction(tx -> tx.findThread(parentId).orElseThrow());
    List<NewThreadCommand> batch = new ArrayList<>();
    for (int i = 0; i < payloads.size(); i++) {
      batch.add(new NewThreadCommand(payloads.get(i), TestIds.id(base + 6 + i)));
    }
    caseRuntime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                parentId, stopped.headEntryId(), stopped.nextCommandSequence()),
            batch),
        AcceptancePreflight.IDENTITY);

    ThreadExecutionControl expected =
        expectResumed ? ThreadExecutionControl.RUNNABLE : ThreadExecutionControl.STOPPED;
    assertEquals(
        expected,
        caseStore.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));
    // 恢复只作用于目标线程：随 Stop 一起暂停的后代保持 STOPPED。
    assertEquals(
        ThreadExecutionControl.STOPPED,
        caseStore.transaction(tx -> tx.findThread(childId).orElseThrow().executionControl()));
  }

  @Test
  void stoppedParentRejectsLateTaskJoinUntilResumed() {
    // 测试意图：STOPPED 父线程拒绝迟到的子任务 Join（持久条件，不依赖 head 形态）；先以真实用户输入恢复父线程后，
    // 同一父 head 上的 Join 才被接受。
    AcceptedCommands root =
        runtime.acceptCommands(session(3100, 3101, null), AcceptancePreflight.IDENTITY);
    UUID parentId = root.thread().id();

    long parentVersion = store.transaction(tx -> tx.findThread(parentId).orElseThrow().version());
    runtime.stop(new StopCommand(parentId, TestIds.id(3102), parentVersion));
    UUID stoppedHead = store.transaction(tx -> tx.findThread(parentId).orElseThrow().headEntryId());

    // 未恢复：子 Session + Join 被拒绝，且不残留 Thread / Join。
    ThreadJoinRequest rejected = request(3103, parentId, stoppedHead);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.acceptCommandsAndJoin(
                session(3104, 3105, parentId), rejected, AcceptancePreflight.IDENTITY));
    assertTrue(runtime.findJoin(rejected.invocationId()).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(3105)).isEmpty()));

    // 接纳真实用户输入后父线程恢复 RUNNABLE（head 不变）。
    ThreadState stopped = store.transaction(tx -> tx.findThread(parentId).orElseThrow());
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                parentId, stopped.headEntryId(), stopped.nextCommandSequence()),
            List.of(userMessageCommand(TestIds.id(3106), "resume parent"))),
        AcceptancePreflight.IDENTITY);
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));

    // 恢复后，同一父 head 上的子任务 Join 被接受。
    ThreadJoinRequest accepted = request(3107, parentId, stoppedHead);
    AcceptedCommands acceptedChild =
        runtime.acceptCommandsAndJoin(
            session(3104, 3105, parentId), accepted, AcceptancePreflight.IDENTITY);
    assertFalse(acceptedChild.replayed());
    assertEquals(
        parentId, runtime.findJoin(accepted.invocationId()).orElseThrow().parentThreadId());
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
