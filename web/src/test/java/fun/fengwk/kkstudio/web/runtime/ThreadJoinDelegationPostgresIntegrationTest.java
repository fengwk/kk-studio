package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
import fun.fengwk.kkstudio.harness.runtime.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * 异步执行树（thread join）在真实 PostgreSQL + 真实 Runtime + 真实 dispatcher/processor 上的端到端集成测试。
 *
 * <p>测试意图：把只有真实事务、真实 advisory 锁、真实 work 调度与真实 Processor 才能证明的事情钉死在集成层。本类完全替换了旧的 {@code
 * SubagentTaskDelegationPostgresIntegrationTest}：不再有 task 表、结算扫描器或 {@code settleOnce} 轮询——推进只由
 * durable work 行驱动（{@link HarnessWorkDispatcher} 从 {@code harness_work} claim 并交给真实 Processor），join
 * 的首次 idle 匹配与父消息交付都在 Processor 的事务里完成。
 *
 * <ul>
 *   <li>配额是硬约束：并发接受共享同一份持久额度事实，越限者整体回滚，绝不留悬挂 Session/Thread/Command/Join；
 *   <li>父暂停 + 恢复：显式 stop 的父只保存已匹配 receipt，绝不唤醒；父真实接受新输入时原子交付旧结果且恰好一次；
 *   <li>并发重复接受：同一 invocation 的并发接受只有一次真正写入，另一侧按既有 command 幂等键重放；
 *   <li>重启无 NOTIFY：接受阶段没有任何调度器在跑，durable work 行是唯一恢复面，调度器启动后自行读取并推进；
 *   <li>首 idle 固定结果：receipt 一旦匹配就冻结在 {@code terminalEntryId}，子执行后续历史绝不改写它；
 *   <li>忙子 resume：向仍在执行（已有未消费命令）的子追加源 prompt 立即被接受，并在真正下一次 Idle 结算；
 *   <li>三层执行树：结果沿不可变 parent 链逐级传递，每级都把下一级的报告带回自己的报告里，父级状态只由自身 durable 执行控制与本地投影表达。
 * </ul>
 *
 * <p>调度隔离：每个用例自持一个真实 {@link HarnessWorkDispatcher} 实例与私有 executor，并在用例结束时停止，因此没有任何用例依赖
 * 其它用例的执行顺序或共享调度状态（测试上下文里生产 dispatcher 由 {@code workers-enabled=false} 保持关闭）。回显模型不产生 tool
 * call，父执行因而绝不会重复委派，用例的树形状完全由测试显式构造。
 *
 * <p>唯一被替换的外部依赖是真实模型：{@link EchoModelGateway} 把最后一条 USER 消息原样回显，因此每个 turn 的产出确定且能证明结果沿树传递。
 * 其余全部是产品路径。等待一律是有界轮询 + 明确超时，不使用固定 sleep。
 */
@Import(ThreadJoinDelegationPostgresIntegrationTest.EchoModelGatewayConfiguration.class)
class ThreadJoinDelegationPostgresIntegrationTest extends WebPostgresTestSupport {

  private static final BranchSettings SETTINGS =
      new BranchSettings(
          "default-assistant", new ModelSelection("stub", "acceptance-stub", "default"), null);

  private static final String AGENT = "default-assistant";

  private static final int MAX_TURNS = 5;

  private static final int MAX_DEPTH = 3;

  private static final int MAX_CONCURRENT_CHILDREN = 2;

  private static final int MAX_CONCURRENT_THREADS = 8;

  /** 有界等待上界：链路本身只需数秒，超时说明状态机没有收敛。 */
  private static final long AWAIT_SECONDS = 60;

  @Autowired private HarnessRuntime runtime;
  @Autowired private HarnessStore store;
  @Autowired private ThreadProcessor threadProcessor;
  @Autowired private ModelProcessor modelProcessor;
  @Autowired private ToolProcessor toolProcessor;
  @Autowired private Clock clock;
  @Autowired private JdbcTemplate jdbc;

  private final List<HarnessWorkDispatcher> testDispatchers = new ArrayList<>();

  /** 用例自持 executor：与 dispatcher 一起在用例结束时停掉，绝不让后台线程跨用例继续改动数据库。 */
  private final List<ExecutorService> testExecutors = new ArrayList<>();

  /**
   * 收紧 dispatcher 的 periodic poll：测试不依赖通知链路，只要 durable work 存在就应快速收敛。
   *
   * <p>该属性同时让本类使用独立 Spring 上下文，本类启动的都是自持 dispatcher，不影响其它测试类。
   */
  @DynamicPropertySource
  static void tightenDispatcherPoll(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.harness.dispatcher.poll-interval", () -> "100ms");
  }

  /** 用回显模型替代真实 Provider：见 {@link EchoModelGateway}。 */
  static class EchoModelGatewayConfiguration {

    @Bean
    @Primary
    EchoModelGateway echoModelGateway() {
      return new EchoModelGateway();
    }
  }

  /**
   * 用例结束必须让调度完全静默：先停止 dispatcher，再关闭并等待其私有 executor 退出，避免残留后台事务与下一个用例的 schema reset 互相
   * 等待（dispatcher.stop 本身不中断已接受的 Processor task）。
   */
  @AfterEach
  void stopTestDispatchers() throws InterruptedException {
    for (HarnessWorkDispatcher dispatcher : testDispatchers) {
      dispatcher.stop();
    }
    testDispatchers.clear();
    for (ExecutorService executor : testExecutors) {
      executor.shutdownNow();
    }
    for (ExecutorService executor : testExecutors) {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test executor did not terminate: " + executor);
      }
    }
    testExecutors.clear();
  }

  /**
   * 父暂停 + 恢复：父本地 turn 已结束时显式 stop 必须写下 durable 停止边界；有界停止传播到子执行后 join 完成首次匹配并冻结
   * receipt，但父已停止因此只保存、不投递、不唤醒；父真实接受新输入时原子交付旧结果，且重放不产生第二次交付。
   */
  @Test
  void stoppedParentHoldsMatchedReceiptUntilGenuineResumeDeliversExactlyOnce() {
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), parentThreadId, "coordinate the work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    acceptChildSession(
        childSessionId,
        childThreadId,
        parentThreadId,
        invocationId,
        headEntryId(parentThreadId),
        "child work");

    // 父仍欠自己的 turn（本地有工作）：durable 执行控制是 RUNNABLE。
    assertEquals(ThreadExecutionControl.RUNNABLE, threadState(parentThreadId).executionControl());

    // 真实 idle Stop：父必须写下自己的 durable STOP 边界，而不是让子结果照常唤醒它。
    UUID stopRequestId = UUID.randomUUID();
    StopResult stop =
        runtime.stop(
            new StopCommand(parentThreadId, stopRequestId, threadState(parentThreadId).version()));
    assertFalse(stop.replayed());
    UUID parentStopBoundary = stoppedTurnEndEntryId(stop, parentThreadId);
    assertNotNull(parentStopBoundary);
    assertStoppedHead(parentThreadId, parentStopBoundary);

    // 停止传播所有执行后代：子命令被取消、子写出 STOP 边界，join 因此完成首次匹配并冻结 receipt。
    // 停止同时解除父自身的本地义务：父不再有排队工作（其停止边界就是它的 head），durable 执行控制为 STOPPED。
    assertEquals(ThreadExecutionControl.STOPPED, threadState(parentThreadId).executionControl());
    ThreadJoin join = joinOf(invocationId);
    assertTrue(join.matched(), "stopped parent must still freeze the child receipt");
    assertNull(join.deliveryCommandSequence());
    assertStoppedHead(childThreadId, threadState(childThreadId).headEntryId());
    assertEquals(ThreadExecutionControl.STOPPED, threadState(childThreadId).executionControl());

    // 精确的子树回执：Stop 覆盖整棵受影响执行子树（父 + 直接子），每个节点各一条回执，携带各自身份的停止请求
    // （root 用请求自身 id，子节点用 root+子身份派生的确定 id）、各自自有停止边界与取消输入，绝不多收无关节点。
    assertEquals(
        Set.of(parentThreadId, childThreadId),
        stop.stoppedThreads().stream()
            .map(StoppedThreadReceipt::threadId)
            .collect(Collectors.toSet()),
        "stop must emit exactly one receipt per stopped node of the execution subtree");
    StoppedThreadReceipt parentReceipt = receipt(stop, parentThreadId);
    StoppedThreadReceipt childReceipt = receipt(stop, childThreadId);
    assertEquals(
        stopRequestId, parentReceipt.stopRequestId(), "root receipt keeps the requested stop id");
    assertNotEquals(
        stopRequestId,
        childReceipt.stopRequestId(),
        "child receipt must not reuse the root stop id");
    assertEquals(
        derivedChildStopRequestId(stopRequestId, childThreadId),
        childReceipt.stopRequestId(),
        "child receipt carries the deterministic root+child derived stop id");
    assertEquals(parentStopBoundary, parentReceipt.stoppedTurnEndEntryId());
    assertEquals(1, parentReceipt.cancelledCommandCount());
    assertEquals(
        "coordinate the work",
        messageText(
            ((UserMessageCommandPayload) parentReceipt.cancelledInputs().getFirst().payload())
                .message()));
    assertEquals(threadState(childThreadId).headEntryId(), childReceipt.stoppedTurnEndEntryId());
    assertEquals(1, childReceipt.cancelledCommandCount());
    assertEquals(1, childReceipt.cancelledInputs().size());
    assertEquals(
        "child work",
        messageText(
            ((UserMessageCommandPayload) childReceipt.cancelledInputs().getFirst().payload())
                .message()));

    ThreadJoinReceipt receipt = receiptOf(invocationId);
    assertEquals(ThreadJoinOutcome.CANCELLED, receipt.outcome());
    assertEquals(childThreadId, receipt.childThreadId());
    assertEquals("child work", receipt.prompt());
    assertTrue(
        resultCommands(parentThreadId).isEmpty(),
        "a stopped parent must not be woken by its child receipt");

    // 父真实接受新输入：先原子交付挂起的旧结果，再插入本次用户命令。
    long resumeSequence = threadState(parentThreadId).nextCommandSequence();
    NewThreadCommand resumePrompt = promptCommand(UUID.randomUUID(), "resume the work");
    AcceptedCommands resumed =
        acceptOnThread(parentThreadId, parentStopBoundary, resumeSequence, resumePrompt);
    assertFalse(resumed.replayed());

    List<ThreadCommand> delivered = resultCommands(parentThreadId);
    assertEquals(1, delivered.size(), "exactly one result message must be delivered on resume");
    assertEquals(invocationId, delivered.getFirst().idempotencyKey());
    String deliveredText = resultMessageText(delivered.getFirst());
    assertTrue(deliveredText.contains("<subagent_result"), deliveredText);
    assertTrue(deliveredText.contains("state=\"cancelled\""), deliveredText);
    assertNotNull(joinOf(invocationId).deliveryCommandSequence());

    // 重放同一批输入（同一幂等键、同一 cursor）不得产生第二次交付。
    AcceptedCommands replayed =
        acceptOnThread(parentThreadId, parentStopBoundary, resumeSequence, resumePrompt);
    assertTrue(replayed.replayed());
    assertEquals(1, resultCommands(parentThreadId).size());
  }

  /**
   * 重复接受同一 invocation：并发接受不可能产生第二套 Session/Thread/Command/Join——败者要么按既有 command 幂等键重放，要么整体
   * 回滚；已提交后的顺序重放必须精确重放（HTTP 重试语义），且持久事实计数保持不变。
   */
  @Test
  void duplicateJoinAcceptanceKeepsExactlyOneChildExecution() throws Exception {
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), parentThreadId, "coordinate the work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID expectedParentHead = headEntryId(parentThreadId);
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(
                childSessionId, childThreadId, SETTINGS, parentThreadId, false),
            List.of(promptCommand(UUID.randomUUID(), "child work")));
    ThreadJoinRequest join =
        taskJoin(invocationId, parentThreadId, expectedParentHead, "child work");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<AcceptedCommands> outcomes;
    try {
      CountDownLatch start = new CountDownLatch(1);
      Callable<AcceptedCommands> acceptance =
          () -> {
            start.await();
            try {
              return runtime.acceptCommandsAndJoin(command, join, AcceptancePreflight.IDENTITY);
            } catch (IllegalArgumentException rejected) {
              // 并发败者整体回滚：不允许留下半套事实。
              return null;
            }
          };
      Future<AcceptedCommands> first = pool.submit(acceptance);
      Future<AcceptedCommands> second = pool.submit(acceptance);
      start.countDown();
      // 败者可能返回 null（整体回滚），因此这里用允许 null 的集合。
      outcomes = new ArrayList<>(2);
      outcomes.add(first.get(AWAIT_SECONDS, TimeUnit.SECONDS));
      outcomes.add(second.get(AWAIT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    // 并发重复接受绝不允许产生第二套事实：恰好一条 Session/Thread/Command/Join，且至少一次接受成立。
    assertTrue(
        outcomes.stream().anyMatch(Objects::nonNull),
        "exactly one concurrent acceptance must succeed");
    assertEquals(1, count("harness_session", "id", childSessionId));
    assertEquals(1, count("harness_thread", "id", childThreadId));
    assertEquals(1, count("harness_thread_command", "thread_id", childThreadId));
    assertEquals(1, count("harness_thread_join", "invocation_id", invocationId));
    assertEquals(1, commandsOf(parentThreadId).size());

    // 已提交后的顺序重放：同一请求（同一 target/命令/幂等键 + 同一 join 身份）必须精确重放，不再创建任何事实。
    AcceptedCommands replay =
        runtime.acceptCommandsAndJoin(command, join, AcceptancePreflight.IDENTITY);
    assertTrue(replay.replayed(), "a committed duplicate acceptance must replay");
    assertEquals(childThreadId, replay.thread().id());
    assertEquals(1, count("harness_session", "id", childSessionId));
    assertEquals(1, count("harness_thread", "id", childThreadId));
    assertEquals(1, count("harness_thread_command", "thread_id", childThreadId));
    assertEquals(1, count("harness_thread_join", "invocation_id", invocationId));

    // 复用同一 invocation 身份但换了目标 Thread 属于协议误用，必须显式拒绝。
    IllegalArgumentException reused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runtime.acceptCommandsAndJoin(
                    new AcceptCommandsCommand(
                        new AcceptCommandsTarget.NewSession(
                            UUID.randomUUID(), UUID.randomUUID(), SETTINGS, parentThreadId, false),
                        List.of(promptCommand(UUID.randomUUID(), "child work"))),
                    join,
                    AcceptancePreflight.IDENTITY));
    assertEquals("join invocation identity reused", reused.getMessage());
  }

  /**
   * 父执行树配额是硬约束：并发接受共享同一份持久额度事实（同序的树锁 + 持久计数），因此恰好 limit 个接受成功；越限者必须整体回滚， 不留悬挂
   * Session/Entry/Thread/Command/Join，也不留下半个 join 凭据。
   */
  @Test
  void concurrentJoinAcceptanceSerializesParentQuotaAndRollsBackLosers() throws Exception {
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), parentThreadId, "coordinate the work");

    int attempts = 6;
    List<UUID> childSessions = new ArrayList<>();
    List<UUID> childThreads = new ArrayList<>();
    List<UUID> invocations = new ArrayList<>();
    for (int index = 0; index < attempts; index++) {
      childSessions.add(UUID.randomUUID());
      childThreads.add(UUID.randomUUID());
      invocations.add(UUID.randomUUID());
    }

    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int index = 0; index < attempts; index++) {
        int attempt = index;
        futures.add(
            pool.submit(
                (Callable<Boolean>)
                    () -> {
                      start.await();
                      try {
                        runtime.acceptCommandsAndJoin(
                            new AcceptCommandsCommand(
                                new AcceptCommandsTarget.NewSession(
                                    childSessions.get(attempt),
                                    childThreads.get(attempt),
                                    SETTINGS,
                                    parentThreadId,
                                    false),
                                List.of(promptCommand(UUID.randomUUID(), "child work"))),
                            taskJoin(
                                invocations.get(attempt),
                                parentThreadId,
                                headEntryId(parentThreadId),
                                "child work"),
                            AcceptancePreflight.IDENTITY);
                        return true;
                      } catch (IllegalArgumentException rejected) {
                        return false;
                      }
                    }));
      }
      start.countDown();
      int accepted = 0;
      for (Future<Boolean> result : futures) {
        if (result.get(AWAIT_SECONDS, TimeUnit.SECONDS)) {
          accepted++;
        }
      }

      // 额度是硬约束：恰好 limit 个接受成功，且成功者与失败者的持久事实严格对应。
      assertEquals(MAX_CONCURRENT_CHILDREN, accepted);
      int acceptedChildren = 0;
      for (int index = 0; index < attempts; index++) {
        if (count("harness_thread", "id", childThreads.get(index)) == 1) {
          acceptedChildren++;
          // 接受成功的子执行必须完整持久化：Session、ROOT Entry、prompt 命令与 join 凭据都在同一事务里。
          assertEquals(1, count("harness_session", "id", childSessions.get(index)));
          assertTrue(count("harness_entry", "session_id", childSessions.get(index)) >= 1);
          assertEquals(1, count("harness_thread_command", "thread_id", childThreads.get(index)));
          assertEquals(1, count("harness_thread_join", "invocation_id", invocations.get(index)));
        } else {
          // 越限失败必须整体回滚：不留悬挂 Session/Entry/Command/Join。
          assertEquals(0, count("harness_session", "id", childSessions.get(index)));
          assertEquals(0, count("harness_entry", "session_id", childSessions.get(index)));
          assertEquals(0, count("harness_thread_command", "thread_id", childThreads.get(index)));
          assertEquals(0, count("harness_thread_join", "invocation_id", invocations.get(index)));
        }
      }
      assertEquals(MAX_CONCURRENT_CHILDREN, acceptedChildren);
      // 父自身保持唯一；父此时仍欠自己的 turn（本地有排队工作），因此 durable 执行控制是 RUNNABLE。
      assertEquals(1, count("harness_thread", "id", parentThreadId));
      assertEquals(ThreadExecutionControl.RUNNABLE, threadState(parentThreadId).executionControl());
    } finally {
      pool.shutdownNow();
    }
  }

  /** 深度配额同样是硬约束：越限的孙执行整体回滚，父与子的既有持久事实不受影响。 */
  @Test
  void joinDepthQuotaRejectionRollsBackGrandChild() {
    UUID rootThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), rootThreadId, "root work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID childInvocationId = UUID.randomUUID();
    acceptedChildSession(
        childSessionId,
        childThreadId,
        rootThreadId,
        childInvocationId,
        headEntryId(rootThreadId),
        "child work",
        2);

    UUID grandChildSessionId = UUID.randomUUID();
    UUID grandChildThreadId = UUID.randomUUID();
    UUID grandChildInvocationId = UUID.randomUUID();
    IllegalArgumentException rejected =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runtime.acceptCommandsAndJoin(
                    new AcceptCommandsCommand(
                        new AcceptCommandsTarget.NewSession(
                            grandChildSessionId,
                            grandChildThreadId,
                            SETTINGS,
                            childThreadId,
                            false),
                        List.of(promptCommand(UUID.randomUUID(), "grandchild work"))),
                    taskJoin(
                        grandChildInvocationId,
                        childThreadId,
                        headEntryId(childThreadId),
                        "grandchild work",
                        2),
                    AcceptancePreflight.IDENTITY));
    assertEquals("join depth quota exceeded", rejected.getMessage());

    // 越限者整体回滚：孙 Session/Thread/命令/join 都不存在，既有的父子事实完整保留。
    assertEquals(0, count("harness_session", "id", grandChildSessionId));
    assertEquals(0, count("harness_thread", "id", grandChildThreadId));
    assertEquals(0, count("harness_thread_command", "thread_id", grandChildThreadId));
    assertEquals(0, count("harness_thread_join", "invocation_id", grandChildInvocationId));
    assertEquals(1, count("harness_session", "id", childSessionId));
    assertEquals(1, count("harness_thread", "id", childThreadId));
    assertEquals(1, count("harness_thread_join", "invocation_id", childInvocationId));
  }

  /**
   * 重启无 NOTIFY：接受发生在没有任何调度器运行时，唯一留下的恢复面就是 durable command/join/work 行（NOTIFY 在 dispatcher
   * 未启动时被丢弃）；随后启动 dispatcher 必须仅凭读取 durable work 完成整条链路。
   */
  @Test
  void acceptedWorkIsRecoveredFromDurableRowsWithoutAnyNotification() {
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), parentThreadId, "coordinate the work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    AcceptedCommands accepted =
        acceptChildSession(
            childSessionId,
            childThreadId,
            parentThreadId,
            invocationId,
            headEntryId(parentThreadId),
            "child work");

    // 没有任何后台推进：子执行仍停在 ROOT，只有 durable 事实（command/join/work）。
    assertEquals(accepted.thread().headEntryId(), threadState(childThreadId).headEntryId());
    assertEquals(1, commandsOf(childThreadId).size());
    assertTrue(hasWork(WorkTargetType.THREAD, childThreadId));
    assertTrue(runtime.projectJoinReceipt(invocationId).isEmpty());
    assertTrue(resultCommands(parentThreadId).isEmpty());

    startTestDispatcher();

    awaitTrue(
        () -> runtime.projectJoinReceipt(invocationId).isPresent(),
        "durable work must be recovered without any notification");
    assertEquals(ThreadJoinOutcome.COMPLETED, receiptOf(invocationId).outcome());
    assertNotNull(joinOf(invocationId).deliveryCommandSequence());
    assertEquals(1, resultCommands(parentThreadId).size());
  }

  /**
   * 首 idle 固定结果：receipt 冻结在首次匹配时的 terminalEntryId；子执行随后被直接继续使用（Thread API 直达子 Thread，不受 join
   * 是否交付影响）并产生不同报告，旧 receipt 仍然保持不变。
   */
  @Test
  void firstIdleFreezesReceiptAndLaterChildHistoryNeverChangesIt() {
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), parentThreadId, "root work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    acceptChildSession(
        childSessionId,
        childThreadId,
        parentThreadId,
        invocationId,
        headEntryId(parentThreadId),
        "child work");

    startTestDispatcher();

    awaitTrue(
        () -> runtime.projectJoinReceipt(invocationId).isPresent(),
        "child must settle at its first idle");
    ThreadJoinReceipt frozen = receiptOf(invocationId);
    assertEquals(ThreadJoinOutcome.COMPLETED, frozen.outcome());
    assertEquals("child work", frozen.prompt());
    assertEquals("ACK:child work", frozen.report());

    ThreadJoin matched = joinOf(invocationId);
    UUID terminalEntryId = matched.terminalEntryId();
    UUID finalAnswerEntryId = matched.finalAnswerEntryId();
    assertNotNull(terminalEntryId);
    assertEquals(
        terminalEntryId,
        threadState(childThreadId).headEntryId(),
        "first idle terminal head is frozen");
    assertNotNull(finalAnswerEntryId, "a completed join freezes its final answer entry");
    assertNotNull(matched.deliveryCommandSequence());

    // 交付结果同样是父线程上的一条真实命令，且 idempotencyKey 就是 invocation 身份。
    List<ThreadCommand> delivered = resultCommands(parentThreadId);
    assertEquals(1, delivered.size());
    assertEquals(invocationId, delivered.getFirst().idempotencyKey());
    assertTrue(resultMessageText(delivered.getFirst()).contains("ACK:child work"));

    // 子 Thread 被直接继续使用：Thread/owner API 不因 join 已存在或已交付而被门禁拦住。
    UUID childHead = headEntryId(childThreadId);
    long childSequence = threadState(childThreadId).nextCommandSequence();
    AcceptedCommands followUp =
        acceptOnThread(
            childThreadId, childHead, childSequence, promptCommand(UUID.randomUUID(), "follow-up"));
    assertFalse(followUp.replayed());

    awaitTrue(
        () -> assistantTexts(runtime.getSessionEntries(childSessionId)).contains("ACK:follow-up"),
        "follow-up turn must be executed by the real processor and produce its own report");
    assertTrue(!headEntryId(childThreadId).equals(childHead), "follow-up must advance the head");

    // 旧 receipt 的冻结不因后续历史而改变。
    ThreadJoin afterFollowUp = joinOf(invocationId);
    assertEquals(terminalEntryId, afterFollowUp.terminalEntryId());
    assertEquals(finalAnswerEntryId, afterFollowUp.finalAnswerEntryId());
    assertEquals("ACK:child work", receiptOf(invocationId).report());
    assertEquals(1, resultCommands(parentThreadId).size());
  }

  /**
   * 忙子 resume：向仍在执行（已有未消费命令）的子追加源 prompt 立即被接受，不要求子静止；两条 join 都在真正下一次 Idle 结算， 每个 invocation
   * 恰好交付一条结果消息。
   */
  @Test
  void busyChildResumeIsAcceptedImmediatelyAndSettlesAtNextIdle() {
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), parentThreadId, "root work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID firstInvocationId = UUID.randomUUID();
    acceptChildSession(
        childSessionId,
        childThreadId,
        parentThreadId,
        firstInvocationId,
        headEntryId(parentThreadId),
        "child work");
    // 子已有接受但尚未消费的命令：resume 必须在"忙"状态下被接受。
    assertEquals(ThreadExecutionControl.RUNNABLE, threadState(childThreadId).executionControl());

    UUID resumeInvocationId = UUID.randomUUID();
    UUID parentHead = headEntryId(parentThreadId);
    long childSequence = threadState(childThreadId).nextCommandSequence();
    NewThreadCommand resumeSource = promptCommand(UUID.randomUUID(), "more child work");
    AcceptedCommands resumed =
        runtime.acceptCommandsAndJoin(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(
                    childThreadId, headEntryId(childThreadId), childSequence),
                List.of(resumeSource)),
            taskJoin(resumeInvocationId, parentThreadId, parentHead, "more child work"),
            AcceptancePreflight.IDENTITY);
    assertFalse(resumed.replayed());
    assertEquals(2, commandsOf(childThreadId).size());

    startTestDispatcher();

    awaitTrue(
        () -> runtime.projectJoinReceipt(resumeInvocationId).isPresent(),
        "resumed join must settle at the next idle");
    ThreadJoin firstJoin = joinOf(firstInvocationId);
    ThreadJoin resumedJoin = joinOf(resumeInvocationId);
    assertTrue(firstJoin.matched());
    assertTrue(resumedJoin.matched());
    assertNotNull(resumedJoin.terminalEntryId(), "resumed join freezes its own terminal entry");
    assertEquals(ThreadJoinOutcome.COMPLETED, receiptOf(firstInvocationId).outcome());
    assertEquals(ThreadJoinOutcome.COMPLETED, receiptOf(resumeInvocationId).outcome());
    assertEquals(
        ThreadRuntimeStatus.IDLE, runtime.getThreadSnapshot(childThreadId).runtimeStatus());

    assertTrue(
        commandsOf(childThreadId).stream().allMatch(command -> command.state().isTerminal()),
        "resumed child must settle every accepted source command");

    List<ThreadCommand> delivered = resultCommands(parentThreadId);
    assertEquals(2, delivered.size(), "each invocation delivers exactly one result message");
    Set<UUID> deliveredInvocations =
        delivered.stream().map(ThreadCommand::idempotencyKey).collect(Collectors.toSet());
    assertEquals(Set.of(firstInvocationId, resumeInvocationId), deliveredInvocations);
  }

  /**
   * 三层执行树：结果沿不可变 parent 链逐级传递——孙执行的报告先交付给子执行，子执行的下一次报告因此包含孙身份，最终父收到子的报告； 执行关系由 parent 链表达，而不是混入
   * Session 对话历史，父级状态只由自身 durable 执行控制表达。
   */
  @Test
  void threeLevelTreePropagatesResultsUpTheParentChain() {
    UUID rootThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), rootThreadId, "root work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID childInvocationId = UUID.randomUUID();
    acceptChildSession(
        childSessionId,
        childThreadId,
        rootThreadId,
        childInvocationId,
        headEntryId(rootThreadId),
        "child work");

    UUID grandChildSessionId = UUID.randomUUID();
    UUID grandChildThreadId = UUID.randomUUID();
    UUID grandChildInvocationId = UUID.randomUUID();
    acceptChildSession(
        grandChildSessionId,
        grandChildThreadId,
        childThreadId,
        grandChildInvocationId,
        headEntryId(childThreadId),
        "grandchild work");

    // 两级父此刻都仍欠各自的 turn：durable 执行控制是 RUNNABLE。
    assertEquals(ThreadExecutionControl.RUNNABLE, threadState(rootThreadId).executionControl());
    assertEquals(ThreadExecutionControl.RUNNABLE, threadState(childThreadId).executionControl());

    startTestDispatcher();

    awaitTrue(
        () -> runtime.projectJoinReceipt(grandChildInvocationId).isPresent(),
        "grandchild must settle first");
    ThreadJoinReceipt grandChildReceipt = receiptOf(grandChildInvocationId);
    assertEquals(ThreadJoinOutcome.COMPLETED, grandChildReceipt.outcome());
    assertEquals("ACK:grandchild work", grandChildReceipt.report());

    awaitTrue(
        () -> runtime.projectJoinReceipt(childInvocationId).isPresent(),
        "child must settle after consuming the grandchild receipt");
    ThreadJoinReceipt childReceipt = receiptOf(childInvocationId);
    assertEquals(ThreadJoinOutcome.COMPLETED, childReceipt.outcome());
    // 子执行的下一次 turn 输入正是孙执行的 receipt，因此报告里必须出现孙 Thread 身份与孙报告。
    assertTrue(
        childReceipt.report().contains(grandChildThreadId.toString()), childReceipt.report());
    assertTrue(childReceipt.report().contains("ACK:grandchild work"), childReceipt.report());

    // 执行树事实来自不可变 parent 链（head-to-root，包含自身）。
    assertEquals(
        List.of(grandChildThreadId, childThreadId, rootThreadId),
        runtime.findAncestorChain(grandChildThreadId));
    assertEquals(List.of(childThreadId, rootThreadId), runtime.findAncestorChain(childThreadId));
    assertEquals(grandChildThreadId, threadState(grandChildThreadId).id());
    assertEquals(childThreadId, threadState(grandChildThreadId).parentThreadId());

    awaitTrue(
        () -> resultCommands(rootThreadId).size() == 1,
        "root must receive the child result exactly once");
    String rootDelivery = resultMessageText(resultCommands(rootThreadId).getFirst());
    assertTrue(rootDelivery.contains(childThreadId.toString()), rootDelivery);
    assertTrue(rootDelivery.contains("state=\"completed\""), rootDelivery);
  }

  /**
   * 父先把自己的 turn 跑完（真实 dispatcher 收敛为本地 IDLE），此时接受子执行：父的 durable 执行控制保持 RUNNABLE，本地投影只按父自身的 事实表达为
   * IDLE，不再递归祖先表达"等孩子"；子执行随后由重新启动的 dispatcher 仅凭 durable work 推进，整棵树最终收敛回 IDLE。
   */
  @Test
  void parentWithActiveChildStaysRunnableWithLocalIdleAndConvergesAfterChildSettles() {
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(UUID.randomUUID(), parentThreadId, "parent work");

    HarnessWorkDispatcher first = startTestDispatcher();
    awaitTrue(
        () -> runtime.getThreadSnapshot(parentThreadId).runtimeStatus() == ThreadRuntimeStatus.IDLE,
        "parent must finish its own turn first");
    // 停止调度构造"父空闲 + 子活跃"的确定窗口：不依赖 sleep，也不依赖别的用例的执行顺序。
    first.stop();

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    acceptChildSession(
        childSessionId,
        childThreadId,
        parentThreadId,
        invocationId,
        headEntryId(parentThreadId),
        "child work");

    // 父没有本地工作但有活跃直接孩子：durable 执行控制保持 RUNNABLE，本地投影只按父自身事实表达为 IDLE，不再有"等孩子"这类递归状态。
    assertEquals(ThreadExecutionControl.RUNNABLE, threadState(parentThreadId).executionControl());
    HarnessThreadDTO parentDto =
        HarnessRuntimeResponseMapper.toThreadDto(runtime.getThreadSnapshot(parentThreadId));
    assertEquals(ThreadRuntimeStatus.IDLE.name(), parentDto.getStatus());
    assertFalse(parentDto.getProcessing());
    assertNull(parentDto.getParentThreadId());
    // 子执行尚未被消费：它对外是排队中（processing），并携带不可变执行父关系。
    HarnessThreadDTO childDto =
        HarnessRuntimeResponseMapper.toThreadDto(runtime.getThreadSnapshot(childThreadId));
    assertEquals(ThreadRuntimeStatus.QUEUED.name(), childDto.getStatus());
    assertTrue(childDto.getProcessing());
    assertEquals(parentThreadId.toString(), childDto.getParentThreadId());
    // 子未收敛前父不可能拿到结果。
    assertFalse(joinOf(invocationId).matched());
    assertTrue(resultCommands(parentThreadId).isEmpty());

    // 重新启动调度：durable work 是唯一恢复面，子执行与父交付都自行收敛。
    startTestDispatcher();
    awaitTrue(
        () -> runtime.projectJoinReceipt(invocationId).isPresent(),
        "restarted dispatcher must settle the child without any notification");
    awaitTrue(
        () ->
            runtime.getThreadSnapshot(childThreadId).runtimeStatus() == ThreadRuntimeStatus.IDLE
                && runtime.getThreadSnapshot(parentThreadId).runtimeStatus()
                    == ThreadRuntimeStatus.IDLE,
        "the whole tree must converge back to IDLE once every descendant settled");
    assertEquals(ThreadJoinOutcome.COMPLETED, receiptOf(invocationId).outcome());
    assertEquals(1, resultCommands(parentThreadId).size());
    assertEquals("ACK:child work", receiptOf(invocationId).report());
  }

  // ------------------------------------------------------------------ fixture

  /** 接受一个根 Session（无执行父关系）。 */
  private AcceptedCommands acceptRootSession(UUID sessionId, UUID threadId, String prompt) {
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(sessionId, threadId, SETTINGS, null, false),
                List.of(promptCommand(UUID.randomUUID(), prompt))),
            AcceptancePreflight.IDENTITY);
    assertEquals(threadId, accepted.thread().id());
    assertFalse(accepted.replayed());
    return accepted;
  }

  /** 以真实 join 接受一个新子 Session：子命令与 join 必须在同一事务内建立。 */
  private AcceptedCommands acceptChildSession(
      UUID childSessionId,
      UUID childThreadId,
      UUID parentThreadId,
      UUID invocationId,
      UUID expectedParentHeadEntryId,
      String prompt) {
    return acceptedChildSession(
        childSessionId,
        childThreadId,
        parentThreadId,
        invocationId,
        expectedParentHeadEntryId,
        prompt,
        MAX_DEPTH);
  }

  /** 指定深度配额的子执行接受。 */
  private AcceptedCommands acceptedChildSession(
      UUID childSessionId,
      UUID childThreadId,
      UUID parentThreadId,
      UUID invocationId,
      UUID expectedParentHeadEntryId,
      String prompt,
      int maxDepth) {
    NewThreadCommand source = promptCommand(UUID.randomUUID(), prompt);
    AcceptedCommands accepted =
        runtime.acceptCommandsAndJoin(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(
                    childSessionId, childThreadId, SETTINGS, parentThreadId, false),
                List.of(source)),
            taskJoin(invocationId, parentThreadId, expectedParentHeadEntryId, prompt, maxDepth),
            AcceptancePreflight.IDENTITY);
    assertEquals(childThreadId, accepted.thread().id());
    assertFalse(accepted.replayed());
    return accepted;
  }

  private AcceptedCommands acceptOnThread(
      UUID threadId,
      UUID expectedHeadEntryId,
      long expectedNextCommandSequence,
      NewThreadCommand command) {
    return runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(
                threadId, expectedHeadEntryId, expectedNextCommandSequence),
            List.of(command)),
        AcceptancePreflight.IDENTITY);
  }

  private static ThreadJoinRequest taskJoin(
      UUID invocationId, UUID parentThreadId, UUID expectedParentHeadEntryId, String prompt) {
    return taskJoin(invocationId, parentThreadId, expectedParentHeadEntryId, prompt, MAX_DEPTH);
  }

  private static ThreadJoinRequest taskJoin(
      UUID invocationId,
      UUID parentThreadId,
      UUID expectedParentHeadEntryId,
      String prompt,
      int maxDepth) {
    return new ThreadJoinRequest(
        invocationId,
        parentThreadId,
        expectedParentHeadEntryId,
        ThreadCommandPayloadJsonCodec.requestHash(
            new UserMessageCommandPayload(AgentMessage.user(prompt))),
        AGENT,
        MAX_TURNS,
        maxDepth,
        MAX_CONCURRENT_CHILDREN,
        MAX_CONCURRENT_THREADS);
  }

  private static NewThreadCommand promptCommand(UUID idempotencyKey, String prompt) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(AgentMessage.user(prompt)), idempotencyKey);
  }

  /**
   * 为一个用例启动自持的真实 dispatcher：使用真实 Processor 与私有 executor，periodic poll 收紧到 20ms，因此只要 durable work
   * 存在就会收敛；用例结束由 {@link #stopTestDispatchers()} 停止，调度状态绝不跨用例共享。
   */
  private HarnessWorkDispatcher startTestDispatcher() {
    Duration lease = Duration.ofSeconds(30);
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            lease, lease, lease, Duration.ofMillis(20), Duration.ofMillis(20), 64);
    ExecutorService drainExecutor =
        Executors.newSingleThreadExecutor(daemonThreads("thread-join-test-drain-"));
    ExecutorService workerExecutor =
        new ThreadPoolExecutor(
            4,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            daemonThreads("thread-join-test-worker-"),
            new ThreadPoolExecutor.AbortPolicy());
    ScheduledExecutorService pollScheduler =
        Executors.newSingleThreadScheduledExecutor(daemonThreads("thread-join-test-poll-"));
    testExecutors.add(drainExecutor);
    testExecutors.add(workerExecutor);
    testExecutors.add(pollScheduler);
    HarnessWorkDispatcher dispatcher =
        new HarnessWorkDispatcher(
            UUID.randomUUID(),
            store,
            config,
            clock,
            drainExecutor,
            workerExecutor,
            pollScheduler,
            threadProcessor,
            modelProcessor,
            toolProcessor);
    testDispatchers.add(dispatcher);
    dispatcher.start();
    return dispatcher;
  }

  private static ThreadFactory daemonThreads(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private ThreadJoin joinOf(UUID invocationId) {
    return store.transaction(tx -> tx.findJoin(invocationId).orElseThrow());
  }

  private ThreadJoinReceipt receiptOf(UUID invocationId) {
    return runtime.projectJoinReceipt(invocationId).orElseThrow();
  }

  private ThreadState threadState(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }

  private UUID headEntryId(UUID threadId) {
    return threadState(threadId).headEntryId();
  }

  private List<ThreadCommand> commandsOf(UUID threadId) {
    return store.transaction(tx -> tx.loadCommandsByThread(threadId));
  }

  /** 父 Thread 上已入队的 join 结果交付命令（CUSTOM_MESSAGE），按 sequence 升序。 */
  private List<ThreadCommand> resultCommands(UUID parentThreadId) {
    return commandsOf(parentThreadId).stream()
        .filter(command -> command.payload() instanceof CustomMessageCommandPayload)
        .toList();
  }

  private boolean hasWork(WorkTargetType type, UUID targetId) {
    return store.transaction(tx -> tx.findWork(new WorkTarget(type, targetId)).isPresent());
  }

  /** 断言 head 恰为停止门禁承认的 STOPPED 边界（非 continuation 的 STOPPED TURN_END）。 */
  private void assertStoppedHead(UUID threadId, UUID expectedTurnEndEntryId) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.findThread(threadId).orElseThrow();
          assertEquals(expectedTurnEndEntryId, thread.headEntryId());
          TurnEndPayload head =
              assertInstanceOf(
                  TurnEndPayload.class, tx.findEntry(thread.headEntryId()).orElseThrow().payload());
          assertEquals(TurnEndOutcome.STOPPED, head.outcome());
          assertFalse(head.continueModel());
          return null;
        });
  }

  /** 从一次 Stop 的完整受影响集合中取出目标 Thread 自己的停止边界。 */
  private static UUID stoppedTurnEndEntryId(StopResult stop, UUID threadId) {
    return receipt(stop, threadId).stoppedTurnEndEntryId();
  }

  /** 取出某个节点的停止回执；缺失即断言失败。 */
  private static StoppedThreadReceipt receipt(StopResult stop, UUID threadId) {
    for (StoppedThreadReceipt receipt : stop.stoppedThreads()) {
      if (receipt.threadId().equals(threadId)) {
        return receipt;
      }
    }
    throw new AssertionError("stop result carries no receipt for thread " + threadId);
  }

  /**
   * 与 Runtime 相同的确定派生：子节点的停止请求 id = {@code nameUUID(rootStopRequestId + ":" + childThreadId)}。 每个
   * Thread 持有独立回执身份，子节点不重用 root 的 id。
   */
  private static UUID derivedChildStopRequestId(UUID rootStopRequestId, UUID childThreadId) {
    return UUID.nameUUIDFromBytes(
        (rootStopRequestId + ":" + childThreadId).getBytes(StandardCharsets.UTF_8));
  }

  private static String resultMessageText(ThreadCommand command) {
    return messageText(((CustomMessageCommandPayload) command.payload()).message());
  }

  private static String messageText(AgentMessage message) {
    StringBuilder text = new StringBuilder();
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent value) {
        text.append(value.text());
      }
    }
    return text.toString();
  }

  private static List<String> assistantTexts(List<Entry> entries) {
    List<String> texts = new ArrayList<>();
    for (Entry entry : entries) {
      if (!(entry.payload() instanceof MessagePayload message)
          || message.message().role() != AgentMessageRole.ASSISTANT) {
        continue;
      }
      texts.add(messageText(message.message()));
    }
    return texts;
  }

  private int count(String table, String column, UUID value) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    return count == null ? 0 : count;
  }

  private static void awaitTrue(BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for: " + message, interrupted);
      }
    }
    throw new AssertionError("timed out waiting for: " + message);
  }
}
