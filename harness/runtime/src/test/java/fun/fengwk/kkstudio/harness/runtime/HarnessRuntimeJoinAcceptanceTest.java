package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.HASH;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.acceptAndJoin;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.child;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.Child;
import fun.fengwk.kkstudio.harness.runtime.store.testing.SpawnTestSupport.Ticket;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.GoalCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class HarnessRuntimeJoinAcceptanceTest {
  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  private static AcceptCommandsCommand session(int session, int thread, UUID parent) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewRootSession(
            TestIds.id(session), TestIds.id(thread), settings(), false),
        List.of(userMessageCommand(TestIds.id(session + 1000), "prompt")));
  }

  private static NewThreadCommand setAgentCommand(UUID idempotencyKey) {
    return new NewThreadCommand(new SetAgentCommandPayload("assistant"), idempotencyKey);
  }

  private static Ticket request(int invocation, UUID parent, UUID head) {
    return Ticket.task(TestIds.id(invocation), parent, head, HASH, "assistant", 3, 3, 2, 3);
  }

  @Test
  void rootTicketAcceptsCommandAndReceiptInSameTransactionAndReplays() {
    // 测试意图：one-shot 从首条源命令起存在 durable receipt，重放不重复插入。
    AcceptCommandsCommand source = session(10, 11, null);
    Ticket ticket = request(12, null, null);
    AcceptedCommands first = acceptAndJoin(runtime, source, ticket, AcceptancePreflight.IDENTITY);
    ThreadJoin join = runtime.findJoin(ticket.invocationId()).orElseThrow();
    assertEquals(first.acceptedCommands().getLast().sequence(), join.sourceCommandSequence());
    assertEquals(ThreadExecutionControl.RUNNABLE, first.thread().executionControl());
    assertFalse(join.matched());
    assertTrue(acceptAndJoin(runtime, source, ticket, AcceptancePreflight.IDENTITY).replayed());
    assertEquals(join, runtime.findJoin(ticket.invocationId()).orElseThrow());
  }

  @Test
  void parentAndNestedChildArePermanentAndJoinFailureRollsBackEverything() {
    // 测试意图：建立不可变三层执行树，preflight 失败时子 session/command/join 一并回滚。
    AcceptedCommands root =
        runtime.acceptCommands(session(20, 21, null), AcceptancePreflight.IDENTITY);
    Ticket firstJoin = request(22, root.thread().id(), root.thread().headEntryId());
    AcceptedCommands first =
        acceptAndJoin(
            runtime, child(23, 24, root.thread().id()), firstJoin, AcceptancePreflight.IDENTITY);
    Ticket secondJoin = request(25, first.thread().id(), first.thread().headEntryId());
    assertThrows(
        IllegalStateException.class,
        () ->
            acceptAndJoin(
                runtime,
                child(26, 27, first.thread().id()),
                secondJoin,
                (tx, current, commands) -> {
                  throw new IllegalStateException("reject");
                }));
    assertTrue(runtime.findJoin(secondJoin.invocationId()).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(27)).isEmpty()));
    assertEquals(
        List.of(first.thread().id(), root.thread().id()),
        store.transaction(tx -> tx.findAncestorChain(first.thread().id())));
    AcceptedCommands second =
        acceptAndJoin(
            runtime, child(26, 27, first.thread().id()), secondJoin, AcceptancePreflight.IDENTITY);
    assertEquals(
        List.of(second.thread().id(), first.thread().id(), root.thread().id()),
        store.transaction(tx -> tx.findAncestorChain(second.thread().id())));
  }

  @Test
  void quotaAndInvocationReuseRejectWithoutOrphanSource() {
    // 测试意图：同一父级未匹配额度拒绝第二子；invocation ID 重用不得写入孤儿源 command。
    AcceptedCommands root =
        runtime.acceptCommands(session(30, 31, null), AcceptancePreflight.IDENTITY);
    UUID parent = root.thread().id();
    acceptAndJoin(
        runtime,
        child(32, 33, parent),
        request(34, parent, root.thread().headEntryId()),
        AcceptancePreflight.IDENTITY);
    Ticket limited =
        Ticket.task(
            TestIds.id(35), parent, root.thread().headEntryId(), HASH, "assistant", 3, 3, 1, 3);
    assertThrows(
        IllegalArgumentException.class,
        () -> acceptAndJoin(runtime, child(36, 37, parent), limited, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(37)).isEmpty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime,
                child(38, 39, parent),
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
    Ticket joinReq = request(42, parentId, root.thread().headEntryId());
    AcceptedCommands child =
        acceptAndJoin(runtime, child(43, 44, parentId), joinReq, AcceptancePreflight.IDENTITY);
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
    Ticket joinReq = request(48, parentId, root.thread().headEntryId());
    AcceptedCommands child =
        acceptAndJoin(runtime, child(49, 50, parentId), joinReq, AcceptancePreflight.IDENTITY);
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

    // 父线程接纳新的 cursor-bound 批次（SET 前缀 + 真实用户输入），落在 seq 3/4。
    NewThreadCommand userCmd = userMessageCommand(TestIds.id(52), "next prompt");
    List<NewThreadCommand> batch = List.of(setAgentCommand(TestIds.id(57)), userCmd);
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(parentId, parent.headEntryId(), 3L), batch),
            AcceptancePreflight.IDENTITY);
    assertEquals(2, accepted.acceptedCommands().size());
    assertEquals(3L, accepted.acceptedCommands().get(0).sequence());

    // client 仍以原始期望序号 2 重放：被跳过的 seq 2 恰是 Join 交付命令，按契约应接受重放。
    AcceptCommandsCommand originalReplay =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(parentId, parent.headEntryId(), 2L), batch);
    AcceptedCommands replayed =
        runtime.acceptCommands(originalReplay, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(3L, replayed.acceptedCommands().getFirst().sequence());
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
    // 测试意图：cursor-bound 批次重放时，若跳过的历史前置序号包含普通用户命令（非 Join 交付）则拒绝重放；
    // 若请求批次内各命令序号不连续同样拒绝。
    AcceptedCommands root =
        runtime.acceptCommands(session(50, 51, null), AcceptancePreflight.IDENTITY);
    UUID threadId = root.thread().id();
    UUID head = root.thread().headEntryId();

    // 连续写入两批 SET_AGENT + 用户命令：seq 2/3 与 seq 4/5。SET 前缀使这些批次保持 cursor-bound。
    NewThreadCommand cmd2 = userMessageCommand(TestIds.id(52), "cmd 2");
    List<NewThreadCommand> batch2 = List.of(setAgentCommand(TestIds.id(55)), cmd2);
    AcceptedCommands acc2 =
        runtime.acceptCommands(
            new AcceptCommandsCommand(new AcceptCommandsTarget.Thread(threadId, head, 2L), batch2),
            AcceptancePreflight.IDENTITY);
    assertEquals(2L, acc2.acceptedCommands().getFirst().sequence());

    NewThreadCommand cmd3 = userMessageCommand(TestIds.id(53), "cmd 3");
    AcceptedCommands acc3 =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(threadId, head, 4L),
                List.of(setAgentCommand(TestIds.id(56)), cmd3)),
            AcceptancePreflight.IDENTITY);
    assertEquals(4L, acc3.acceptedCommands().getFirst().sequence());

    // 尝试重放 batch2，但传入 expected = 1L（前置 seq 1 不是 delivery），触发
    // allPrecedingWereDeliveries = false -> 拒绝。
    AcceptCommandsCommand nonDeliveryPreceding =
        new AcceptCommandsCommand(new AcceptCommandsTarget.Thread(threadId, head, 1L), batch2);
    assertThrows(
        HarnessRuntimeConflictException.class,
        () -> runtime.acceptCommands(nonDeliveryPreceding, AcceptancePreflight.IDENTITY));

    // 尝试重放不连续的命令批次：[cmd2, cmd3]
    AcceptCommandsCommand nonContiguousBatch =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(threadId, head, 2L), List.of(cmd2, cmd3));
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
    Ticket join1 = request(62, parentId, parent.thread().headEntryId());
    AcceptedCommands child =
        acceptAndJoin(runtime, child(63, 64, parentId), join1, AcceptancePreflight.IDENTITY);
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
        new AcceptCommandsCommand(new AcceptCommandsTarget.Thread(childId), List.of(childCmd)),
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
    Ticket join1 = Ticket.task(TestIds.id(72), parentId, parentHead, HASH, "assistant", 3, 3, 1, 5);
    AcceptedCommands child1 =
        acceptAndJoin(runtime, child(73, 74, parentId), join1, AcceptancePreflight.IDENTITY);
    UUID child1Id = child1.thread().id();
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));

    // 第二个子任务：未完成 Join 计数已达上限 -> 拒绝，且不留下 Thread / Join 残留。
    Ticket join2 = Ticket.task(TestIds.id(75), parentId, parentHead, HASH, "assistant", 3, 3, 1, 5);
    assertThrows(
        IllegalArgumentException.class,
        () -> acceptAndJoin(runtime, child(76, 77, parentId), join2, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(77)).isEmpty()));
    assertTrue(runtime.findJoin(join2.invocationId()).isEmpty());

    // 停止 child1：Join 在执行终止边界冻结为已完成，parent 子任务额度随之释放。
    long child1Version = store.transaction(tx -> tx.findThread(child1Id).orElseThrow().version());
    runtime.stop(new StopCommand(child1Id, TestIds.id(78), child1Version));
    assertTrue(runtime.findJoin(join1.invocationId()).orElseThrow().matched());
    assertEquals(0, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));

    // 额度释放后，第二个子任务被接受。
    AcceptedCommands child2 =
        acceptAndJoin(runtime, child(76, 77, parentId), join2, AcceptancePreflight.IDENTITY);
    assertFalse(child2.replayed());
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));

    // 深度配额：新子线程深度为 2，超过 maxDepth = 1 -> 拒绝（与子任务额度无关）。
    Ticket tooDeep =
        Ticket.task(TestIds.id(79), parentId, parentHead, HASH, "assistant", 3, 1, 5, 5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(runtime, child(80, 81, parentId), tooDeep, AcceptancePreflight.IDENTITY));
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
    Ticket a1 = Ticket.task(TestIds.id(2202), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    AcceptedCommands childA1 =
        acceptAndJoin(runtime, child(2203, 2204, parentA), a1, AcceptancePreflight.IDENTITY);
    Ticket b1 = Ticket.task(TestIds.id(2212), parentB, headB, HASH, "assistant", 3, 3, 5, 2);
    acceptAndJoin(runtime, child(2213, 2214, parentB), b1, AcceptancePreflight.IDENTITY);
    assertEquals(2, activeSubagentJoinCount());

    // root ticket（parentThreadId 为空）不占全局 subagent 额度：额度已满时仍可创建。
    AcceptedCommands rootTicket =
        acceptAndJoin(
            runtime,
            session(2217, 2218, null),
            request(2219, null, null),
            AcceptancePreflight.IDENTITY);
    assertFalse(rootTicket.replayed());
    assertNull(runtime.findJoin(TestIds.id(2219)).orElseThrow().parentThreadId());
    assertEquals(2, activeSubagentJoinCount());

    // 全局额度已满：A 树自己只有 1 个子任务，但 B 树的 childB1 占用全局额度 -> 拒绝，且无残留。
    Ticket a2 = Ticket.task(TestIds.id(2205), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    assertThrows(
        IllegalArgumentException.class,
        () -> acceptAndJoin(runtime, child(2206, 2207, parentA), a2, AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(2207)).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findJoin(TestIds.id(2205)).isEmpty()));

    // exact replay 命中既有 join，不重复占额度。
    AcceptedCommands replayed =
        acceptAndJoin(runtime, child(2203, 2204, parentA), a1, AcceptancePreflight.IDENTITY);
    assertTrue(replayed.replayed());
    assertEquals(2, activeSubagentJoinCount());

    // 停止 childA1：Join 冻结为已完成 -> 全局额度释放一个。
    long childA1Version =
        store.transaction(tx -> tx.findThread(childA1.thread().id()).orElseThrow().version());
    runtime.stop(new StopCommand(childA1.thread().id(), TestIds.id(2215), childA1Version));
    assertTrue(runtime.findJoin(a1.invocationId()).orElseThrow().matched());
    assertEquals(1, activeSubagentJoinCount());

    // 额度释放后，A 下的新子任务再次被接受。
    Ticket a3 = Ticket.task(TestIds.id(2208), parentA, headA, HASH, "assistant", 3, 3, 5, 2);
    AcceptedCommands childA3 =
        acceptAndJoin(runtime, child(2209, 2220, parentA), a3, AcceptancePreflight.IDENTITY);
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

    // 1. 票据 parent 与既有 Thread 的不可变 parent 不一致 -> 拒绝（见下）。
    // 2. join parent 与 target.parentThreadId 不一致 -> 拒绝
    Ticket diffParent = request(94, TestIds.id(999), parentHead);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime, child(95, 96, parentId), diffParent, AcceptancePreflight.IDENTITY));

    // 3. parent headEntryId 不匹配 -> 拒绝
    Ticket wrongHead = request(97, parentId, TestIds.id(888));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime, child(98, 99, parentId), wrongHead, AcceptancePreflight.IDENTITY));

    // 4. parent 被真实 Stop 置为 STOPPED -> 拒绝新子任务（STOPPED 是持久条件，不再依赖 head 形态）
    long parentVersion = store.transaction(tx -> tx.findThread(parentId).orElseThrow().version());
    runtime.stop(new StopCommand(parentId, TestIds.id(100), parentVersion));
    UUID stoppedHead = store.transaction(tx -> tx.findThread(parentId).orElseThrow().headEntryId());
    assertEquals(
        ThreadExecutionControl.STOPPED,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));
    Ticket stoppedParentJoin = request(101, parentId, stoppedHead);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime,
                child(102, 103, parentId),
                stoppedParentJoin,
                AcceptancePreflight.IDENTITY));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(103)).isEmpty()));
    assertTrue(runtime.findJoin(stoppedParentJoin.invocationId()).isEmpty());

    // 5. 不存在 parentId -> 拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime,
                child(103, 104, TestIds.id(777)),
                request(105, TestIds.id(777), stoppedHead),
                AcceptancePreflight.IDENTITY));

    // 6. 源命令重放时缺失 Join：在普通线程先以无 join 接受命令，随后再尝试带 join 重放相同命令 -> 拒绝
    AcceptedCommands normal =
        runtime.acceptCommands(session(106, 107, null), AcceptancePreflight.IDENTITY);
    NewThreadCommand cmdAlone = userMessageCommand(TestIds.id(108), "alone");
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(normal.thread().id()), List.of(cmdAlone)),
        AcceptancePreflight.IDENTITY);

    Ticket newJoinOnReplayed = request(109, null, null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime,
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(
                        normal.thread().id(),
                        normal.thread().headEntryId(),
                        normal.thread().nextCommandSequence()),
                    List.of(cmdAlone)),
                newJoinOnReplayed,
                AcceptancePreflight.IDENTITY));

    // 7. Join invocation identity 被以不同参数（如不同 agent）重用 -> 拒绝
    Ticket initialJoin = request(110, null, null);
    acceptAndJoin(runtime, session(111, 112, null), initialJoin, AcceptancePreflight.IDENTITY);

    Ticket conflictedJoin =
        Ticket.task(TestIds.id(110), null, null, HASH, "different-agent", 3, 3, 2, 3);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime, session(113, 114, null), conflictedJoin, AcceptancePreflight.IDENTITY));
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
                new AcceptCommandsTarget.NewRootSession(
                    TestIds.id(121), TestIds.id(122), settings(), false),
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
                new AcceptCommandsTarget.NewRootSession(
                    TestIds.id(124), TestIds.id(125), settings(), false),
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
    Ticket join = request(base + 2, parentId, root.thread().headEntryId());
    AcceptedCommands child =
        acceptAndJoin(
            caseRuntime, child(base + 3, base + 4, parentId), join, AcceptancePreflight.IDENTITY);
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
            AcceptCommandsCommand.isPureUserMessageBatch(batch)
                ? new AcceptCommandsTarget.Thread(parentId)
                : new AcceptCommandsTarget.Thread(
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
    Ticket rejected = request(3103, parentId, stoppedHead);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            acceptAndJoin(
                runtime, child(3104, 3105, parentId), rejected, AcceptancePreflight.IDENTITY));
    assertTrue(runtime.findJoin(rejected.invocationId()).isEmpty());
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(TestIds.id(3105)).isEmpty()));

    // 接纳真实用户输入后父线程恢复 RUNNABLE（head 不变）。
    ThreadState stopped = store.transaction(tx -> tx.findThread(parentId).orElseThrow());
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(parentId),
            List.of(userMessageCommand(TestIds.id(3106), "resume parent"))),
        AcceptancePreflight.IDENTITY);
    assertEquals(
        ThreadExecutionControl.RUNNABLE,
        store.transaction(tx -> tx.findThread(parentId).orElseThrow().executionControl()));

    // 恢复后，同一父 head 上的子任务 Join 被接受。
    Ticket accepted = request(3107, parentId, stoppedHead);
    AcceptedCommands acceptedChild =
        acceptAndJoin(runtime, child(3104, 3105, parentId), accepted, AcceptancePreflight.IDENTITY);
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
    Ticket join = request(2102, parentId, root.thread().headEntryId());
    Child child = child(1, 2, parentId);

    AcceptedCommands created = acceptAndJoin(runtime, child, join, AcceptancePreflight.IDENTITY);
    assertFalse(created.replayed());
    assertTrue(TestIds.id(1).equals(created.session().id()));
    assertTrue(TestIds.id(2).equals(created.thread().id()));

    AcceptedCommands replayed = acceptAndJoin(runtime, child, join, AcceptancePreflight.IDENTITY);
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
                        sessionId, TestIds.id(999), TestIds.id(132), "fork", false),
                    List.of(userMessageCommand(TestIds.id(133), "fork"))),
                AcceptancePreflight.IDENTITY));

    // 2. replayInitial idempotencyKey 重用但 requestHash 不一致 -> IDEMPOTENCY_KEY_REUSED
    NewThreadCommand initCmd = userMessageCommand(TestIds.id(134), "initial message");
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(
                TestIds.id(135), TestIds.id(136), settings(), false),
            List.of(initCmd)),
        AcceptancePreflight.IDENTITY);

    NewThreadCommand tamperedHashCmd =
        new NewThreadCommand(initCmd.payload(), TestIds.id(134), "b".repeat(64));
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewRootSession(
                        TestIds.id(135), TestIds.id(136), settings(), false),
                    List.of(tamperedHashCmd)),
                AcceptancePreflight.IDENTITY));

    // 3. replayInitial 不同的 creationRequestHash (不同 sessionId) -> THREAD_ID_REUSED
    assertThrows(
        HarnessRuntimeConflictException.class,
        () ->
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.NewRootSession(
                        TestIds.id(999), TestIds.id(136), settings(), false),
                    List.of(initCmd)),
                AcceptancePreflight.IDENTITY));
  }

  /**
   * 续接同一子线程：新 invocation 原子上位 supersede 既有未完成 join，只保留一份有效 join，旧 join 保留 invocation identity 与排队源
   * prompt，且不占额外配额；同 invocation 重放幂等且不重复 supersede。
   */
  @Test
  void continuationSupersedesUnfinishedJoinKeepingIdentityAndQueuedPrompt() {
    AcceptedCommands parent =
        runtime.acceptCommands(session(900, 901, null), AcceptancePreflight.IDENTITY);
    UUID parentId = parent.thread().id();
    UUID parentHead = parent.thread().headEntryId();

    Ticket first = request(902, parentId, parentHead);
    AcceptedCommands child =
        acceptAndJoin(runtime, child(903, 904, parentId), first, AcceptancePreflight.IDENTITY);
    UUID childId = child.thread().id();
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));

    ThreadState childState = store.transaction(tx -> tx.findThread(childId).orElseThrow());
    UUID secondInvocation = TestIds.id(905);
    Ticket second =
        Ticket.task(secondInvocation, parentId, parentHead, HASH, "assistant", 3, 3, 2, 3);
    AcceptCommandsCommand continuation =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                childId, childState.headEntryId(), childState.nextCommandSequence()),
            List.of(userMessageCommand(TestIds.id(906), "more work")));

    AcceptedCommands continued =
        acceptAndJoin(runtime, continuation, second, AcceptancePreflight.IDENTITY);
    assertTrue(continued.joinReplaced());

    ThreadJoin oldJoin = runtime.findJoin(first.invocationId()).orElseThrow();
    assertFalse(oldJoin.matched());
    assertEquals(secondInvocation, oldJoin.supersededByInvocationId());
    // 旧 join 保留其 invocation identity 与排队源 prompt（source command 仍在，未被删除）。
    assertTrue(
        store.<Boolean>transaction(
            tx -> tx.findCommand(childId, oldJoin.sourceCommandSequence()).isPresent()));
    ThreadJoin newJoin = runtime.findJoin(secondInvocation).orElseThrow();
    assertNull(newJoin.supersededByInvocationId());
    // 同一父/子对至多一个有效未完成 join：计数仍为 1，且只有新 join 属有效集合。
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));
    assertEquals(
        List.of(secondInvocation),
        store.<List<UUID>>transaction(
            tx -> tx.loadIncompleteJoins(childId).stream().map(ThreadJoin::invocationId).toList()));

    // 同 invocation 重放幂等：不重复 supersede，也不改写既有 join。
    AcceptedCommands replay =
        acceptAndJoin(runtime, continuation, second, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed());
    assertFalse(replay.joinReplaced());
    assertEquals(oldJoin, runtime.findJoin(first.invocationId()).orElseThrow());
    assertEquals(newJoin, runtime.findJoin(secondInvocation).orElseThrow());
  }

  /** 不计额度的票据（如压缩的 SETTLE_OWNER_TURN）不占父子/全局配额，也不计入未完成执行子 Join 计数。 */
  @Test
  void nonQuotaTicketDoesNotConsumeTaskQuota() {
    AcceptedCommands parent =
        runtime.acceptCommands(session(910, 911, null), AcceptancePreflight.IDENTITY);
    UUID parentId = parent.thread().id();
    UUID parentHead = parent.thread().headEntryId();

    Ticket settle = Ticket.settle(TestIds.id(912), parentId, HASH, "compactor");
    AcceptedCommands settleChild =
        acceptAndJoin(runtime, child(913, 914, parentId), settle, AcceptancePreflight.IDENTITY);

    ThreadJoin join = runtime.findJoin(settle.invocationId()).orElseThrow();
    assertEquals(JoinDelivery.SETTLE_OWNER_TURN, join.delivery());
    assertFalse(join.countsTowardQuota());
    assertEquals(0, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));
    assertEquals(0, store.<Integer>transaction(tx -> tx.countIncompleteSubagentJoins()));

    // 因此父的唯一 task 子任务额度仍可用。
    Ticket task = Ticket.task(TestIds.id(915), parentId, parentHead, HASH, "assistant", 3, 3, 1, 1);
    acceptAndJoin(runtime, child(916, 917, parentId), task, AcceptancePreflight.IDENTITY);
    assertEquals(1, store.<Integer>transaction(tx -> tx.countIncompleteChildJoins(parentId)));
    assertNotNull(settleChild.thread().id());
  }

  /**
   * 子 Thread 的有效约束是请求与父约束按 min/OR/AND 合并后的冻结值；续用既有 Thread 时沿用首次创建冻结的来源与约束， 本次请求的同名字段（即使是
   * USER/NONE）不能改写它们。
   */
  @Test
  void spawnFreezesMergedConstraintsAndContinuationCannotRewriteThem() {
    BranchSettings settings = settings();
    NewThreadCommand rootCommand = userMessageCommand(TestIds.id(4001), "root");
    SpawnRequest rootRequest =
        new SpawnRequest(
            TestIds.id(4000),
            TestIds.id(4002),
            settings,
            List.of(rootCommand),
            AcceptancePreflight.IDENTITY,
            null,
            null,
            null,
            false,
            new ThreadConstraints(1_000, false, false),
            SpawnTrigger.CONTROLLER,
            JoinDelivery.RECEIPT_ONLY,
            false,
            SpawnRequest.Budget.unlimited(),
            TestIds.id(4003),
            rootCommand.requestHash());
    AcceptedCommands root = runtime.spawn(rootRequest);
    assertEquals(new ThreadConstraints(1_000, false, false), root.thread().constraints());
    assertEquals(SpawnTrigger.CONTROLLER, root.thread().spawnTrigger());

    NewThreadCommand childCommand = userMessageCommand(TestIds.id(4004), "child");
    SpawnRequest childRequest =
        new SpawnRequest(
            TestIds.id(4005),
            TestIds.id(4006),
            settings,
            List.of(childCommand),
            AcceptancePreflight.IDENTITY,
            root.thread().id(),
            root.thread().headEntryId(),
            null,
            false,
            new ThreadConstraints(2_000, true, true),
            SpawnTrigger.MODEL_TOOL,
            JoinDelivery.NOTIFY_PARENT,
            true,
            SpawnRequest.Budget.unlimited(),
            TestIds.id(4007),
            childCommand.requestHash());
    AcceptedCommands child = runtime.spawn(childRequest);
    assertEquals(new ThreadConstraints(1_000, true, false), child.thread().constraints());
    assertEquals(SpawnTrigger.MODEL_TOOL, child.thread().spawnTrigger());
    ThreadJoin childJoin = runtime.findJoin(TestIds.id(4007)).orElseThrow();
    assertEquals(JoinDelivery.NOTIFY_PARENT, childJoin.delivery());
    assertTrue(childJoin.countsTowardQuota());

    ThreadState current = runtime.getThreadSnapshot(child.thread().id()).thread();
    NewThreadCommand resumedCommand = userMessageCommand(TestIds.id(4008), "more");
    SpawnRequest resumedRequest =
        new SpawnRequest(
            child.thread().id(),
            null,
            settings,
            List.of(resumedCommand),
            AcceptancePreflight.IDENTITY,
            root.thread().id(),
            root.thread().headEntryId(),
            new SpawnRequest.Continuation(current.headEntryId(), current.nextCommandSequence()),
            false,
            ThreadConstraints.NONE,
            SpawnTrigger.CONTROLLER,
            JoinDelivery.NOTIFY_PARENT,
            true,
            SpawnRequest.Budget.unlimited(),
            TestIds.id(4009),
            resumedCommand.requestHash());
    AcceptedCommands resumed = runtime.spawn(resumedRequest);

    assertEquals(SpawnTrigger.MODEL_TOOL, resumed.thread().spawnTrigger());
    assertEquals(new ThreadConstraints(1_000, true, false), resumed.thread().constraints());
    ThreadJoin resumedJoin = runtime.findJoin(TestIds.id(4009)).orElseThrow();
    assertEquals(JoinDelivery.NOTIFY_PARENT, resumedJoin.delivery());
    assertTrue(resumedJoin.countsTowardQuota());
  }

  /** 统一 spawn 在写任何行前复用 accept 的 batch 形状规则；非法批次必须是参数错误而不是 ClassCast 或部分写入。 */
  @Test
  void spawnRejectsInvalidBatchShapesBeforeAnyWrite() {
    NewThreadCommand first = userMessageCommand(TestIds.id(5001), "first");
    NewThreadCommand second = userMessageCommand(TestIds.id(5002), "second");
    NewThreadCommand setAgent =
        new NewThreadCommand(new SetAgentCommandPayload("worker"), TestIds.id(5003));
    NewThreadCommand setModel =
        new NewThreadCommand(
            new SetModelCommandPayload(ModelSelection.model("model", "v1")), TestIds.id(5004));

    assertSpawnRejected(List.of(first, second));
    assertSpawnRejected(List.of(setAgent));
    assertSpawnRejected(List.of(setModel, setAgent, first));
    assertSpawnRejected(List.of(first, setAgent));
  }

  private void assertSpawnRejected(List<NewThreadCommand> commands) {
    UUID sessionId = TestIds.id(5100 + commands.size());
    UUID threadId = TestIds.id(5200 + commands.size());
    UUID ticketId = TestIds.id(5300 + commands.size());
    SpawnRequest request =
        new SpawnRequest(
            threadId,
            sessionId,
            settings(),
            commands,
            AcceptancePreflight.IDENTITY,
            null,
            null,
            null,
            false,
            ThreadConstraints.NONE,
            SpawnTrigger.CONTROLLER,
            JoinDelivery.RECEIPT_ONLY,
            false,
            SpawnRequest.Budget.unlimited(),
            ticketId,
            commands.getFirst().requestHash());

    assertThrows(IllegalArgumentException.class, () -> runtime.spawn(request));
    assertTrue(store.transaction(tx -> tx.findSession(sessionId)).isEmpty());
    assertTrue(store.transaction(tx -> tx.findThread(threadId)).isEmpty());
    assertTrue(runtime.findJoin(ticketId).isEmpty());
  }
}
