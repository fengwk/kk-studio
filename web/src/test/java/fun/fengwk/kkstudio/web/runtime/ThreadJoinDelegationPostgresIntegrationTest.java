package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.StopResult;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 *   <li>首 idle 固定结果：receipt 一旦匹配就冻结在 {@code resultHeadEntryId}，子执行后续历史绝不改写它；
 *   <li>三层执行树：结果沿不可变 parent 链逐级传递，每级都把下一级的报告带回自己的报告里；
 *   <li>父暂停 + 恢复：显式 stop 的父只保存已匹配 receipt，绝不唤醒；父真实接受新输入时原子交付旧结果且恰好一次；
 *   <li>并发重复接受：同一 invocation 的并发接受只有一次真正写入，另一侧按既有 command 幂等键重放；
 *   <li>忙子 resume：向仍在执行（已有未消费命令）的子追加源 prompt 立即被接受，并在真正下一次 Idle 结算；
 *   <li>重启无 NOTIFY：接受阶段没有任何调度器在跑，durable work 行是唯一恢复面，调度器启动后自行读取并推进。
 * </ul>
 *
 * <p>唯一被替换的外部依赖是真实模型：{@link EchoModelGateway} 把最后一条 USER 消息原样回显，因此每个 turn 的产出确定且能证明结果沿树传递。
 * 其余全部是产品路径。等待一律是有界轮询 + 明确超时，不使用固定 sleep。
 */
@Import(ThreadJoinDelegationPostgresIntegrationTest.EchoModelGatewayConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
  @Autowired private HarnessWorkDispatcher dispatcher;
  @Autowired private JdbcTemplate jdbc;

  /**
   * 收紧 dispatcher 的 periodic poll：测试不依赖通知链路，只要 durable work 存在就应快速收敛。
   *
   * <p>该属性同时让本类使用独立 Spring 上下文：本类会显式启动共享的 {@link HarnessWorkDispatcher}，不得影响其它测试类。
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
   * 父暂停 + 恢复：父本地 turn 已结束时显式 stop 必须写下 durable 停止边界；有界停止传播到子执行后 join 完成首次匹配并冻结
   * receipt，但父已停止因此只保存、不投递、不唤醒；父真实接受新输入时原子交付旧结果，且重放不产生第二次交付。
   */
  @Test
  @Order(1)
  void stoppedParentHoldsMatchedReceiptUntilGenuineResumeDeliversExactlyOnce() {
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(parentSessionId, parentThreadId, "coordinate the work");

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

    // 父本地已静止但仍有活跃直接孩子：递归生命周期必须是 WAITING_CHILDREN。
    assertEquals(ThreadLifecycleStatus.WAITING_CHILDREN, threadState(parentThreadId).status());

    // 真实 idle Stop：父必须写下自己的 durable STOP 边界，而不是让子结果照常唤醒它。
    StopResult stop =
        runtime.stop(
            new StopCommand(
                parentThreadId, UUID.randomUUID(), threadState(parentThreadId).version()));
    assertFalse(stop.replayed());
    assertNotNull(stop.stoppedTurnEndEntryId());
    assertStoppedHead(parentThreadId, stop.stoppedTurnEndEntryId());

    // 停止传播所有执行后代：子命令被取消、子写出 STOP 边界，join 因此完成首次匹配并冻结 receipt。
    ThreadJoin join = joinOf(invocationId);
    assertTrue(join.matched(), "stopped parent must still freeze the child receipt");
    assertNull(join.deliveryCommandSequence());
    assertStoppedHead(childThreadId, threadState(childThreadId).headEntryId());
    assertEquals(ThreadLifecycleStatus.IDLE, threadState(childThreadId).status());

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
        acceptOnThread(parentThreadId, stop.stoppedTurnEndEntryId(), resumeSequence, resumePrompt);
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
        acceptOnThread(parentThreadId, stop.stoppedTurnEndEntryId(), resumeSequence, resumePrompt);
    assertTrue(replayed.replayed());
    assertEquals(1, resultCommands(parentThreadId).size());
  }

  /**
   * 并发重复接受：同一 invocation 的两次并发接受共享同一份持久事实，只有一次真正创建子执行，另一侧按 command 幂等键重放， 绝不产生第二套
   * Session/Thread/Command/Join。
   */
  @Test
  @Order(2)
  void concurrentDuplicateJoinAcceptanceCreatesExactlyOneChildExecution() throws Exception {
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(parentSessionId, parentThreadId, "coordinate the work");

    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    UUID expectedParentHead = headEntryId(parentThreadId);
    NewThreadCommand source = promptCommand(UUID.randomUUID(), "child work");
    AcceptCommandsCommand command =
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewSession(
                childSessionId, childThreadId, SETTINGS, parentThreadId, false),
            List.of(source));
    ThreadJoinRequest join =
        taskJoin(invocationId, parentThreadId, expectedParentHead, "child work");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Callable<AcceptedCommands> acceptance =
          () -> {
            start.await();
            return runtime.acceptCommandsAndJoin(command, join, AcceptancePreflight.IDENTITY);
          };
      Future<AcceptedCommands> first = pool.submit(acceptance);
      Future<AcceptedCommands> second = pool.submit(acceptance);
      start.countDown();
      AcceptedCommands firstAccepted = first.get(AWAIT_SECONDS, TimeUnit.SECONDS);
      AcceptedCommands secondAccepted = second.get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertEquals(childThreadId, firstAccepted.thread().id());
      assertEquals(childThreadId, secondAccepted.thread().id());
      int replayed = (firstAccepted.replayed() ? 1 : 0) + (secondAccepted.replayed() ? 1 : 0);
      assertEquals(1, replayed, "exactly one of the duplicate acceptances replays");
      assertEquals(1, count("harness_session", "id", childSessionId));
      assertEquals(1, count("harness_thread", "id", childThreadId));
      assertEquals(1, count("harness_thread_command", "thread_id", childThreadId));
      assertEquals(1, count("harness_thread_join", "invocation_id", invocationId));
      assertEquals(1, commandsOf(parentThreadId).size());
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 重启无 NOTIFY：接受发生在没有任何调度器运行时，唯一留下的恢复面就是 durable command/join/work 行（NOTIFY 在 dispatcher
   * 未启动时被丢弃）；随后启动 dispatcher 必须仅凭读取 durable work 完成整条链路。
   */
  @Test
  @Order(3)
  void acceptedWorkIsRecoveredFromDurableRowsWithoutAnyNotification() {
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(parentSessionId, parentThreadId, "coordinate the work");

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

    startDispatcher();

    awaitTrue(
        () -> runtime.projectJoinReceipt(invocationId).isPresent(),
        "durable work must be recovered without any notification");
    assertEquals(ThreadJoinOutcome.COMPLETED, receiptOf(invocationId).outcome());
    assertNotNull(joinOf(invocationId).deliveryCommandSequence());
    assertEquals(1, resultCommands(parentThreadId).size());
  }

  /**
   * 首 idle 固定结果：receipt 冻结在首次匹配时的 resultHeadEntryId；子执行随后被直接继续使用（Thread API 直达子 Thread，不受 join
   * 是否交付影响）并产生不同报告，旧 receipt 仍然保持不变。
   */
  @Test
  @Order(4)
  void firstIdleFreezesReceiptAndLaterChildHistoryNeverChangesIt() {
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(parentSessionId, parentThreadId, "root work");

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

    startDispatcher();

    awaitTrue(
        () -> runtime.projectJoinReceipt(invocationId).isPresent(),
        "child must settle at its first idle");
    ThreadJoinReceipt frozen = receiptOf(invocationId);
    assertEquals(ThreadJoinOutcome.COMPLETED, frozen.outcome());
    assertEquals("child work", frozen.prompt());
    assertEquals("ACK:child work", frozen.report());

    ThreadJoin matched = joinOf(invocationId);
    UUID resultHeadEntryId = matched.resultHeadEntryId();
    long matchedIdleVersion = matched.matchedIdleVersion();
    assertNotNull(resultHeadEntryId);
    assertTrue(matched.afterVersion() < matchedIdleVersion);
    assertEquals(
        resultHeadEntryId, threadState(childThreadId).headEntryId(), "first idle head is frozen");
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
        () -> !headEntryId(childThreadId).equals(childHead),
        "follow-up turn must be executed by the real processor");
    assertTrue(
        assistantTexts(runtime.getSessionEntries(childSessionId)).contains("ACK:follow-up"),
        "follow-up turn must produce its own report");

    // 旧 receipt 的冻结不因后续历史而改变。
    ThreadJoin afterFollowUp = joinOf(invocationId);
    assertEquals(resultHeadEntryId, afterFollowUp.resultHeadEntryId());
    assertEquals(matchedIdleVersion, afterFollowUp.matchedIdleVersion());
    assertEquals("ACK:child work", receiptOf(invocationId).report());
    assertEquals(1, resultCommands(parentThreadId).size());
  }

  /**
   * 忙子 resume：向仍在执行（已有未消费命令）的子追加源 prompt 立即被接受，不要求子静止；两条 join 都在真正下一次 Idle 结算， 每个 invocation
   * 恰好交付一条结果消息。
   */
  @Test
  @Order(5)
  void busyChildResumeIsAcceptedImmediatelyAndSettlesAtNextIdle() {
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptRootSession(parentSessionId, parentThreadId, "root work");

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
    assertEquals(ThreadLifecycleStatus.ACTIVE, threadState(childThreadId).status());

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

    startDispatcher();

    awaitTrue(
        () -> runtime.projectJoinReceipt(resumeInvocationId).isPresent(),
        "resumed join must settle at the next idle");
    ThreadJoin firstJoin = joinOf(firstInvocationId);
    ThreadJoin resumedJoin = joinOf(resumeInvocationId);
    assertTrue(firstJoin.matched());
    assertTrue(resumedJoin.matched());
    assertTrue(resumedJoin.matchedIdleVersion() >= firstJoin.matchedIdleVersion());
    assertEquals(ThreadJoinOutcome.COMPLETED, receiptOf(firstInvocationId).outcome());
    assertEquals(ThreadJoinOutcome.COMPLETED, receiptOf(resumeInvocationId).outcome());
    assertEquals(ThreadLifecycleStatus.IDLE, threadState(childThreadId).status());
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
   * Session 对话历史。
   */
  @Test
  @Order(6)
  void threeLevelTreePropagatesResultsUpTheParentChain() {
    UUID rootSessionId = UUID.randomUUID();
    UUID rootThreadId = UUID.randomUUID();
    acceptRootSession(rootSessionId, rootThreadId, "root work");

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

    startDispatcher();

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
    NewThreadCommand source = promptCommand(UUID.randomUUID(), prompt);
    AcceptedCommands accepted =
        runtime.acceptCommandsAndJoin(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(
                    childSessionId, childThreadId, SETTINGS, parentThreadId, false),
                List.of(source)),
            taskJoin(invocationId, parentThreadId, expectedParentHeadEntryId, prompt),
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
    return new ThreadJoinRequest(
        invocationId,
        parentThreadId,
        expectedParentHeadEntryId,
        ThreadCommandPayloadJsonCodec.requestHash(
            new UserMessageCommandPayload(AgentMessage.user(prompt))),
        AGENT,
        MAX_TURNS,
        MAX_DEPTH,
        MAX_CONCURRENT_CHILDREN,
        MAX_CONCURRENT_THREADS);
  }

  private static NewThreadCommand promptCommand(UUID idempotencyKey, String prompt) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(AgentMessage.user(prompt)), idempotencyKey);
  }

  /** 只在需要真实执行的用例里启动共享 dispatcher；{@code start} 幂等，启动后一直由真实调度推进。 */
  private void startDispatcher() {
    dispatcher.start();
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

  private static String resultMessageText(ThreadCommand command) {
    CustomMessageCommandPayload payload = (CustomMessageCommandPayload) command.payload();
    StringBuilder text = new StringBuilder();
    for (AgentMessageContent content : payload.message().contents()) {
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
      for (AgentMessageContent content : message.message().contents()) {
        if (content instanceof TextMessageContent value) {
          texts.add(value.text());
        }
      }
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
