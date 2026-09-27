package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
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
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskActivity;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskDraft;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskRejectedException;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskSettlementScanner;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskStatus;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 异步 task 委派在真实 PostgreSQL + 真实 Runtime 上的持久化集成测试。
 *
 * <p>测试意图：把只有真实事务、真实 advisory 锁、真实 Runtime 与确定性 barrier 才能证明的事情钉死在集成层——
 *
 * <ul>
 *   <li>接受是单事务动作：并发接受在持久额度上串行化，越限者的子 Session/Thread/Entry/Command 随事务一起消失（不留半成品）；
 *   <li>父 Thread 停止时结果只持久保留（不注入、不唤醒），head 前进（恢复）后由扫描交付，且恰好一条结果消息（重复扫描不重复交付）；
 *   <li>扫描推进 DELIVERED 与父 head 的前进/停止并发时严格原子：绝不出现"结果命令已入队但记录未推进"或反之；
 *   <li>子执行在"投影终态"与"结算锁定"之间被推进时旧终态绝不结算（锁定复核按 head/version/命令序列判定漂移）， 交付竞争中停止边界先行时 preflight
 *       让事务整体回滚（记录保持 SETTLED、队列 0 条），丢失的交付绝不修补成 DELIVERED。
 * </ul>
 *
 * <p>并发用例一律用确定性 barrier（spy + latch）或真实 PG 锁等待观测来同步：不靠 sleep 碰运气，也不人为制造假竞态。
 *
 * <p>本类只通过生产入口 {@link SubagentTaskSettlementScanner#settleOnce()} 触发结算，并把后台扫描周期放大到测试时长之外， 因此断言不依赖
 * sleep，也不与后台调度竞争。
 *
 * <p>唯一由测试直接写入的 durable 事实是"父 head 前进/停止"这类历史形态：它是 stop 与恢复执行在持久层的结果， 用 store 原语写入可避免引入真实 Provider
 * 依赖，同时仍然经过与生产一致的 {@code EntryPath} 校验。
 */
class SubagentTaskDelegationPostgresIntegrationTest extends WebPostgresTestSupport {

  private static final BranchSettings SETTINGS =
      new BranchSettings(
          "default-assistant", new ModelSelection("stub", "acceptance-stub", "default"), null);

  private static final String PARENT_PROMPT = "delegate the work";

  private static final String CHILD_PROMPT = "perform delegated work";

  @Autowired private HarnessRuntime runtime;
  @Autowired private HarnessStore store;
  @Autowired private SubagentTaskRepository repository;
  @Autowired private SubagentTaskSettlementScanner scanner;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  /** 真实活动聚合的 Mockito spy：只在"投影竞态"测试里用确定性 barrier 拦截一次聚合调用，其余调用全部透传。 */
  @MockitoSpyBean private SubagentTaskActivity activity;

  /** 后台扫描周期放大到测试时长之外：结算只由测试显式触发，避免断言与后台调度竞争。 */
  @DynamicPropertySource
  static void decoupleBackgroundSettlement(DynamicPropertyRegistry registry) {
    registry.add("kk-studio.harness.task.settlement-interval", () -> "1h");
  }

  @Test
  void concurrentChildAcceptanceSerializesQuotaAndRollsBackLosers() throws Exception {
    // 并发接受必须共享同一份持久额度事实：临界区与 SubagentTaskRunner.insertTaskPreflight 同序
    // （事务级额度锁 -> 持久计数 -> 插入记录），因此越限者不能"先写入子 Session/Thread 再失败"，必须整体回滚。
    int limit = 2;
    int attempts = 6;
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptParentSession(parentSessionId, parentThreadId);

    List<UUID> childSessions = new ArrayList<>();
    List<UUID> childThreads = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      childSessions.add(UUID.randomUUID());
      childThreads.add(UUID.randomUUID());
    }

    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        UUID childSessionId = childSessions.get(i);
        UUID childThreadId = childThreads.get(i);
        UUID invocationId = UUID.randomUUID();
        futures.add(
            pool.submit(
                (Callable<Boolean>)
                    () -> {
                      start.await();
                      try {
                        runtime.acceptCommands(
                            new AcceptCommandsCommand(
                                new AcceptCommandsTarget.NewSession(
                                    childSessionId, childThreadId, SETTINGS, null, false),
                                List.of(
                                    command(
                                        new UserMessageCommandPayload(
                                            AgentMessage.user(CHILD_PROMPT))))),
                            (tx, session, commands) -> {
                              repository.lockQuota(parentThreadId, parentThreadId);
                              int open = repository.countOpenByParentThreadId(parentThreadId);
                              if (open >= limit) {
                                throw new SubagentTaskRejectedException(
                                    "subagent concurrency limit reached for this parent");
                              }
                              ThreadState child =
                                  tx.findThread(childThreadId)
                                      .orElseThrow(
                                          () ->
                                              new SubagentTaskRejectedException(
                                                  "child thread was not created"));
                              repository.insert(
                                  new SubagentTaskDraft(
                                      invocationId,
                                      parentThreadId,
                                      parentThreadId,
                                      childSessionId,
                                      childThreadId,
                                      child.headEntryId(),
                                      "default-assistant",
                                      CHILD_PROMPT,
                                      3,
                                      SubagentTaskStatus.OPEN,
                                      0L));
                              return commands;
                            });
                        return true;
                      } catch (SubagentTaskRejectedException rejected) {
                        return false;
                      }
                    }));
      }
      start.countDown();
      int accepted = 0;
      for (Future<Boolean> result : futures) {
        if (result.get(60, TimeUnit.SECONDS)) {
          accepted++;
        }
      }

      // 额度是硬约束：恰好 limit 个接受成功，持久记录数与之严格一致。
      assertEquals(limit, accepted);
      assertEquals(limit, countOpenTasks(parentThreadId));

      int acceptedChildren = 0;
      for (int i = 0; i < attempts; i++) {
        boolean childExists = count("harness_thread", "id", childThreads.get(i)) == 1;
        if (childExists) {
          acceptedChildren++;
          // 接受成功的子执行必须完整持久化：Session、ROOT Entry 与本次 prompt 命令都在同一事务里。
          assertEquals(1, count("harness_session", "id", childSessions.get(i)));
          assertTrue(count("harness_entry", "session_id", childSessions.get(i)) >= 1);
          assertEquals(1, count("harness_thread_command", "thread_id", childThreads.get(i)));
        } else {
          // 越限失败必须整体回滚：不留悬挂 Session/Entry/Command。
          assertEquals(0, count("harness_session", "id", childSessions.get(i)));
          assertEquals(0, count("harness_entry", "session_id", childSessions.get(i)));
          assertEquals(0, count("harness_thread_command", "thread_id", childThreads.get(i)));
        }
      }
      assertEquals(limit, acceptedChildren);
      assertEquals(1, count("harness_thread", "id", parentThreadId));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void idleStopOfParentIsDurableAndDefersDeliveryUntilHeadAdvances() {
    // 父 Thread 等异步子委派时本地 turn 已结束：显式 idle Stop 必须写下 durable 停止边界（旧实现是 no-op，扫描器会照常
    // 唤醒父）。停止后结果只持久保留（SETTLED）、不注入也不唤醒；父恢复（head 前进）后由扫描交付，且恰好一次。
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptParentSession(parentSessionId, parentThreadId);
    UUID invocationId = seedSettledTask(parentThreadId, "report text");

    // 真实 runtime.stop：父是 idle（只有排队命令，没有 live turn），Stop 必须持久化自己的 STOP 边界。
    StopResult stop =
        runtime.stop(
            new StopCommand(
                parentThreadId, UUID.randomUUID(), threadStateOf(parentThreadId).version()));
    assertFalse(stop.replayed());
    assertNotNull(stop.stoppedTurnEndEntryId());
    assertEquals(stop.stoppedTurnEndEntryId(), threadStateOf(parentThreadId).headEntryId());
    assertStoppedHead(parentThreadId, stop.stoppedTurnEndEntryId());

    assertEquals(0, scanner.settleOnce(), "stopped parent must not be woken by a settlement scan");
    assertEquals("SETTLED", taskStatus(invocationId));
    assertEquals(0, deliveredCommands(parentThreadId).size());
    // 重复扫描同样保持 SETTLED 与 0 条：绝不把"父已停止时的交付"修补成 DELIVERED。
    assertEquals(0, scanner.settleOnce());
    assertEquals("SETTLED", taskStatus(invocationId));
    assertEquals(0, deliveredCommands(parentThreadId).size());

    appendResumeTurnStart(parentThreadId);

    assertEquals(1, scanner.settleOnce());
    assertEquals("DELIVERED", taskStatus(invocationId));
    List<ThreadCommand> delivered = deliveredCommands(parentThreadId);
    assertEquals(1, delivered.size());
    assertTrue(messageText(delivered.getFirst()).contains("report text"));
    // 交付只入队命令，不启动新一轮：head 仍是恢复后的 TURN_START。
    UUID resumedHeadEntryId = headEntryId(parentThreadId);
    assertInstanceOf(
        TurnStartPayload.class,
        store.transaction(tx -> tx.findEntry(resumedHeadEntryId).orElseThrow().payload()));

    assertEquals(0, scanner.settleOnce(), "already delivered row must not be delivered twice");
    assertEquals(1, deliveredCommands(parentThreadId).size());
  }

  @Test
  void idleStopPropagatesToRunningChildAndDeliversCancelledExactlyOnceAfterResume() {
    // 父本地 turn 已结束但 OPEN 子委派仍在跑：父必须仍呈现 processing；父 idle Stop 后停止有界传播到子执行（真实
    // runtime.stop 子 Thread 写下 STOP 边界），子结清为 CANCELLED 但结果因父已停止而挂起；父恢复后恰好交付一次。
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptParentSession(parentSessionId, parentThreadId);
    Seed seed = seedOpenRunningChild(parentThreadId);

    // OPEN 子执行仍在跑：父（本地 turn 已结束）必须仍呈现 processing，停止尚未确认传播前不得当作已静止。
    assertTrue(
        activity.hasPendingDelegatedWork(parentThreadId),
        "running child keeps the parent processing");

    StopResult stop =
        runtime.stop(
            new StopCommand(
                parentThreadId, UUID.randomUUID(), threadStateOf(parentThreadId).version()));
    assertNotNull(stop.stoppedTurnEndEntryId());
    assertStoppedHead(parentThreadId, stop.stoppedTurnEndEntryId());

    // 扫描把停止传播到子执行：子被真实停成 STOP barrier Turn，本次不结算、也不注入父。
    scanner.settleOnce();
    assertEquals("OPEN", taskStatus(seed.invocationId()));
    assertEquals(0, deliveredCommands(parentThreadId).size());
    assertStoppedHead(seed.childThreadId(), threadStateOf(seed.childThreadId()).headEntryId());

    // 下一轮以子执行的终态（CANCELLED）结算；父仍停止，因此只持久保留、不唤醒。
    assertEquals(0, scanner.settleOnce());
    assertEquals("SETTLED", taskStatus(seed.invocationId()));
    assertEquals("CANCELLED", taskOutcome(seed.invocationId()));
    assertEquals(0, deliveredCommands(parentThreadId).size());
    // 子树已结清且父已停止：父不再有活动。
    assertFalse(activity.hasPendingDelegatedWork(parentThreadId));
    assertStoppedHead(parentThreadId, stop.stoppedTurnEndEntryId());

    // 父恢复（新 user turn）后恰好交付一次取消结果。
    appendResumeTurnStart(parentThreadId);
    assertEquals(1, scanner.settleOnce());
    assertEquals("DELIVERED", taskStatus(seed.invocationId()));
    List<ThreadCommand> delivered = deliveredCommands(parentThreadId);
    assertEquals(1, delivered.size());
    assertTrue(messageText(delivered.getFirst()).contains("Cancelled by user"));
    // 重复扫描不再产生第二条结果消息（稳定幂等键 + 同事务 CAS）。
    assertEquals(0, scanner.settleOnce());
    assertEquals(1, deliveredCommands(parentThreadId).size());
  }

  @Test
  void concurrentStopBoundaryRaceKeepsDeliveryAtomic() throws Exception {
    // 扫描推进 DELIVERED 与父 head 前进/停止并发时，交付事务必须原子——可观察结果只允许两种：
    // (a) 记录 DELIVERED 且恰好一条结果命令；(b) 记录仍未交付且没有结果命令。绝不出现半状态。
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int round = 0; round < 6; round++) {
        UUID parentSessionId = UUID.randomUUID();
        UUID parentThreadId = UUID.randomUUID();
        acceptParentSession(parentSessionId, parentThreadId);
        UUID invocationId = seedSettledTask(parentThreadId, "report " + round);
        boolean stopBoundary = round % 2 == 0;

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> scan =
            pool.submit(
                () -> {
                  start.await();
                  return scanner.settleOnce();
                });
        Future<Void> boundary =
            pool.submit(
                () -> {
                  start.await();
                  if (stopBoundary) {
                    appendStoppedTurnBoundary(parentThreadId);
                  } else {
                    appendResumeTurnStart(parentThreadId);
                  }
                  return null;
                });
        start.countDown();
        boundary.get(60, TimeUnit.SECONDS);
        scan.get(60, TimeUnit.SECONDS);

        String status = taskStatus(invocationId);
        int delivered = deliveredCommands(parentThreadId).size();
        if ("DELIVERED".equals(status)) {
          assertEquals(1, delivered, "DELIVERED row must have exactly one delivered command");
        } else {
          assertEquals("SETTLED", status);
          assertEquals(0, delivered, "undelivered row must not have inserted a result command");
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void staleTerminalNeverSettlesWhenTheChildExecutionAdvancesUnderTheScan() throws Exception {
    // 投影竞态：扫描读到"子执行已终结"的快照，但在它的结算事务拿到子 Thread 锁之前，子执行又被推进了
    // （例如子级委派的结果刚被交付进该子 Thread、命令被消费产生新 turn、或历史被回退）。
    // 旧终态不再是这次执行的结局：记录必须保持 OPEN、队列 0 条，等下一轮以最新事实重新投影。
    //
    // 竞态用确定性 barrier 制造而不是靠抢锁：扫描读到子快照、进入"活动聚合"时停在 latch 上（此刻它还没有任何锁），
    // 主线程真实提交一次推进（完整新 turn，head/version/nextCommandSequence 全部前进），再放行扫描去锁定比对。
    // 用抢锁去阻塞第一次快照读是假竞态：快照读本身也走 Thread 锁，读到的新终态本来就是当次执行的结局。
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptParentSession(parentSessionId, parentThreadId);
    Seed seed = seedOpenTaskWithTerminalChild(parentThreadId, "first report");
    ThreadState childBeforeAdvance = threadStateOf(seed.childThreadId());

    CountDownLatch snapshotObserved = new CountDownLatch(1);
    CountDownLatch allowProceed = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              snapshotObserved.countDown();
              awaitRelease(allowProceed);
              return invocation.callRealMethod();
            })
        .when(activity)
        .hasPendingDelegatedWork(seed.childThreadId());

    ExecutorService pool = Executors.newFixedThreadPool(1);
    try {
      Future<Integer> scan = pool.submit(() -> scanner.settleOnce());
      assertTrue(snapshotObserved.await(60, TimeUnit.SECONDS), "扫描必须先读到旧终态快照");
      appendCompletedTurnBoundary(seed.childThreadId(), "raced report");
      allowProceed.countDown();
      scan.get(60, TimeUnit.SECONDS);

      assertEquals("OPEN", taskStatus(seed.invocationId()), "旧终态不得被当成这次执行的结局结算");
      assertEquals(0, deliveredCommands(parentThreadId).size());
      assertEquals(
          childBeforeAdvance.version() + 1,
          threadStateOf(seed.childThreadId()).version(),
          "竞争方必须真的推进了子执行");

      // 推进后的子执行重新到达终态：下一轮以最新事实投影后必须能正常结算并交付（门禁只延后，不阻塞），
      // 且交付内容来自推进后的新事实而不是被门禁拦下的旧终态。
      assertEquals(1, scanner.settleOnce());
      assertEquals("DELIVERED", taskStatus(seed.invocationId()));
      List<ThreadCommand> delivered = deliveredCommands(parentThreadId);
      assertEquals(1, delivered.size());
      assertTrue(messageText(delivered.getFirst()).contains("raced report"));
    } finally {
      allowProceed.countDown();
      reset(activity);
      pool.shutdownNow();
    }
  }

  @Test
  void stopBoundaryWinningTheDeliveryRaceNeverFakesDelivery() throws Exception {
    // 交付的真实竞态：扫描读到"父未停止"，随后停止边界在交付事务之前提交。
    // preflight 必须在事务内拒绝这次注入并让事务回滚：队列 0 条结果消息，记录保持 SETTLED（绝不是 DELIVERED），
    // 也绝不在事务外修补状态——否则一次真实交付会被永久吞掉。父恢复后由下一轮交付。
    UUID parentSessionId = UUID.randomUUID();
    UUID parentThreadId = UUID.randomUUID();
    acceptParentSession(parentSessionId, parentThreadId);
    UUID invocationId = seedSettledTask(parentThreadId, "race report");

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch released = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> stopper =
          pool.submit(
              () -> {
                UUID parentSession = threadStateOf(parentThreadId).sessionId();
                store.transaction(
                    tx -> {
                      tx.lockSessionForKeyShare(parentSession).orElseThrow();
                      ThreadState parent = tx.lockThread(parentThreadId).orElseThrow();
                      locked.countDown();
                      awaitRelease(released);
                      appendStoppedTurnBoundaryWithin(tx, parent);
                      return null;
                    });
                return null;
              });
      assertTrue(locked.await(60, TimeUnit.SECONDS));

      Future<Integer> scan = pool.submit(() -> scanner.settleOnce());
      awaitDeliveryOrSettlementLockWaiter();

      released.countDown();
      stopper.get(60, TimeUnit.SECONDS);
      scan.get(60, TimeUnit.SECONDS);

      assertEquals("SETTLED", taskStatus(invocationId), "lost race must not fake delivery");
      assertEquals(0, deliveredCommands(parentThreadId).size());

      // 重复扫描仍然保持 SETTLED 且 0 条：事务外的状态修补绝不允许发生。
      assertEquals(0, scanner.settleOnce());
      assertEquals("SETTLED", taskStatus(invocationId));
      assertEquals(0, deliveredCommands(parentThreadId).size());

      // 父 head 前进（恢复）后由扫描正常交付，恰好一条。
      appendResumeTurnStart(parentThreadId);
      assertEquals(1, scanner.settleOnce());
      assertEquals("DELIVERED", taskStatus(invocationId));
      assertEquals(1, deliveredCommands(parentThreadId).size());
    } finally {
      pool.shutdownNow();
    }
  }

  // ------------------------------------------------------------------ fixture

  private void acceptParentSession(UUID sessionId, UUID threadId) {
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(sessionId, threadId, SETTINGS, null, false),
                List.of(command(new UserMessageCommandPayload(AgentMessage.user(PARENT_PROMPT))))),
            AcceptancePreflight.IDENTITY);
    assertEquals(threadId, accepted.thread().id());
    assertEquals(1, accepted.acceptedCommands().size());
  }

  /** 有界等待：等到出现真实锁等待者（扫描已读到目标事实并阻塞在交付/结算事务的锁上），避免用固定 sleep 碰运气。 */
  private void awaitDeliveryOrSettlementLockWaiter() {
    Instant deadline = Instant.now().plusSeconds(60);
    while (Instant.now().isBefore(deadline)) {
      Integer waiters =
          jdbc.queryForObject(
              "select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
              Integer.class);
      if (waiters != null && waiters > 0) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "interrupted while waiting for the lock waiter", interrupted);
      }
    }
    throw new IllegalStateException("no transaction ever blocked on the delegation locks");
  }

  private static void awaitRelease(CountDownLatch released) {
    try {
      if (!released.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("lock holder was never released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while holding the lock", interrupted);
    }
  }

  /** OPEN 委派记录 + 仍在跑的子执行（委派 prompt 已被消费，尚无终态）。 */
  private Seed seedOpenRunningChild(UUID parentThreadId) {
    UUID invocationId = UUID.randomUUID();
    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(
                    childSessionId, childThreadId, SETTINGS, null, false),
                List.of(command(new UserMessageCommandPayload(AgentMessage.user(CHILD_PROMPT))))),
            AcceptancePreflight.IDENTITY);
    assertEquals(childThreadId, accepted.thread().id());
    UUID sourceHeadEntryId = accepted.thread().headEntryId();
    appendOpenRunningTurn(childThreadId);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                assertTrue(
                    repository.insert(
                        new SubagentTaskDraft(
                            invocationId,
                            parentThreadId,
                            parentThreadId,
                            childSessionId,
                            childThreadId,
                            sourceHeadEntryId,
                            "default-assistant",
                            CHILD_PROMPT,
                            3,
                            SubagentTaskStatus.OPEN,
                            0L))));
    return new Seed(invocationId, childThreadId);
  }

  /** 子执行消费掉委派 prompt 并开启一个 open INPUT turn（还没有终态）：本次执行仍在跑。 */
  private void appendOpenRunningTurn(UUID childThreadId) {
    store.transaction(
        tx -> {
          ThreadState child = tx.lockThread(childThreadId).orElseThrow();
          Instant base = headCreatedAt(tx, child);
          List<ThreadCommand> queued = tx.loadQueuedCommands(childThreadId);
          if (!queued.isEmpty()) {
            Instant cancelAt = base.plusMillis(1);
            tx.updateCommands(
                queued.stream()
                    .map(command -> command.cancel(UUID.randomUUID(), cancelAt))
                    .toList());
          }
          Entry turnStart =
              insertEntry(
                  tx,
                  child,
                  child.headEntryId(),
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, childThreadId),
                  base.plusMillis(2));
          Entry input =
              insertEntry(
                  tx,
                  child,
                  turnStart.id(),
                  new MessagePayload(AgentMessage.user(CHILD_PROMPT), null, null),
                  base.plusMillis(3));
          tx.updateThread(child.advanceHead(input.id(), base.plusMillis(4)));
          return null;
        });
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

  /** OPEN 记录 + 已终结的子执行（真实子 Session/Thread，不是伪造行）。 */
  private Seed seedOpenTaskWithTerminalChild(UUID parentThreadId, String report) {
    UUID invocationId = UUID.randomUUID();
    UUID childSessionId = UUID.randomUUID();
    UUID childThreadId = UUID.randomUUID();
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewSession(
                    childSessionId, childThreadId, SETTINGS, null, false),
                List.of(command(new UserMessageCommandPayload(AgentMessage.user(CHILD_PROMPT))))),
            AcceptancePreflight.IDENTITY);
    assertEquals(childThreadId, accepted.thread().id());
    UUID sourceHeadEntryId = accepted.thread().headEntryId();
    appendCompletedTurnBoundary(childThreadId, report);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                assertTrue(
                    repository.insert(
                        new SubagentTaskDraft(
                            invocationId,
                            parentThreadId,
                            parentThreadId,
                            childSessionId,
                            childThreadId,
                            sourceHeadEntryId,
                            "default-assistant",
                            CHILD_PROMPT,
                            3,
                            SubagentTaskStatus.OPEN,
                            0L))));
    return new Seed(invocationId, childThreadId);
  }

  /**
   * 子执行到达终态：消费掉委派 prompt 的排队命令，再写入 TURN_START → ASSISTANT → TURN_END(COMPLETED)。
   *
   * <p>这是"子执行已结束且静止"的持久形态，也是结算扫描投影终态的依据。
   */
  private void appendCompletedTurnBoundary(UUID childThreadId, String report) {
    store.transaction(
        tx -> {
          ThreadState child = tx.lockThread(childThreadId).orElseThrow();
          List<ThreadCommand> queued = tx.loadQueuedCommands(childThreadId);
          if (!queued.isEmpty()) {
            // 静止判定要求没有排队命令：委派 prompt 的排队命令在这里被显式取消（等价于它没有被任何 turn 消费）。
            Instant cancelAt = headCreatedAt(tx, child).plusMillis(1);
            tx.updateCommands(
                queued.stream()
                    .map(command -> command.cancel(UUID.randomUUID(), cancelAt))
                    .toList());
          }
          completeTurnWithin(tx, child, report);
          return null;
        });
  }

  /** 在已锁住的目标 Thread 上写入一个完整 turn：TURN_START → USER → ASSISTANT → TURN_END(COMPLETED)，并推进 head。 */
  private void completeTurnWithin(HarnessStore.Transaction tx, ThreadState thread, String report) {
    Instant base = headCreatedAt(tx, thread);
    Entry turnStart =
        insertEntry(
            tx,
            thread,
            thread.headEntryId(),
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, thread.id()),
            base.plusMillis(1));
    Entry input =
        insertEntry(
            tx,
            thread,
            turnStart.id(),
            new MessagePayload(AgentMessage.user(CHILD_PROMPT), null, null),
            base.plusMillis(2));
    Entry assistant =
        insertEntry(
            tx,
            thread,
            input.id(),
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(report))),
                assistantMetadata(),
                null),
            base.plusMillis(3));
    Entry turnEnd =
        insertEntry(
            tx,
            thread,
            assistant.id(),
            new TurnEndPayload(turnStart.id(), TurnEndOutcome.COMPLETED, false, null, null),
            base.plusMillis(4));
    tx.updateThread(thread.advanceHead(turnEnd.id(), base.plusMillis(5)));
  }

  /** 在已锁住父 Thread 的同一事务里写入停止边界（停止与交付竞争的形态）。 */
  private void appendStoppedTurnBoundaryWithin(HarnessStore.Transaction tx, ThreadState thread) {
    appendStopBarrierTurnWithin(tx, thread, UUID.randomUUID());
  }

  private static AssistantMessageMetadata assistantMetadata() {
    return new AssistantMessageMetadata(
        GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 1L, 0L, 0L, 0L, 0L, 2L),
        new ModelCost(
            "USD",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.valueOf(2L)));
  }

  private UUID childSessionIdOf(UUID childThreadId) {
    return threadStateOf(childThreadId).sessionId();
  }

  private ThreadState threadStateOf(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }

  /** 一次委派 fixture：记录 id 与真实子 Thread id。 */
  private record Seed(UUID invocationId, UUID childThreadId) {}

  /** 插入一条已终结（SETTLED）的委派记录：子执行已结束、父通知尚未交付，这是结算扫描的起点。 */
  private UUID seedSettledTask(UUID parentThreadId, String report) {
    UUID invocationId = UUID.randomUUID();
    UUID sourceHeadEntryId = headEntryId(parentThreadId);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              assertTrue(
                  repository.insert(
                      new SubagentTaskDraft(
                          invocationId,
                          parentThreadId,
                          parentThreadId,
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          sourceHeadEntryId,
                          "default-assistant",
                          CHILD_PROMPT,
                          3,
                          SubagentTaskStatus.OPEN,
                          0L)));
              assertTrue(
                  repository.settleResult(invocationId, Outcome.COMPLETED, report, null, null));
            });
    return invocationId;
  }

  private void appendStoppedTurnBoundary(UUID threadId) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          appendStopBarrierTurnWithin(tx, thread, UUID.randomUUID());
          return null;
        });
  }

  /**
   * 在已锁定的 Thread 上写入停止边界：与生产 idle Stop 同域的 STOP barrier Turn（TURN_START(STOP) →
   * ASSISTANT_ERROR(CANCELLED) → TURN_END(STOPPED, closeRequestId)），并把 head 推进到该 TURN_END。
   *
   * <p>它用于必须在"已持有目标 Thread 锁"的事务里制造停止边界的并发场景（真实 {@code runtime.stop} 需要自己获取该锁）； 真实 Stop 契约本身由
   * {@link #idleStopOfParentIsDurableAndDefersDeliveryUntilHeadAdvances} 直接验证。
   */
  private void appendStopBarrierTurnWithin(
      HarnessStore.Transaction tx, ThreadState thread, UUID stopRequestId) {
    Instant base = headCreatedAt(tx, thread);
    Entry turnStart =
        insertEntry(
            tx,
            thread,
            thread.headEntryId(),
            new TurnStartPayload(TurnStartReason.STOP, SETTINGS, thread.id()),
            base.plusMillis(1));
    Entry barrier =
        insertEntry(
            tx,
            thread,
            turnStart.id(),
            new AssistantErrorPayload(new AssistantError("CANCELLED", "Cancelled by user"), null),
            base.plusMillis(2));
    Entry turnEnd =
        insertEntry(
            tx,
            thread,
            barrier.id(),
            new TurnEndPayload(
                turnStart.id(),
                TurnEndOutcome.STOPPED,
                false,
                TurnEndReason.USER_STOP,
                stopRequestId),
            base.plusMillis(3));
    tx.updateThread(thread.advanceHead(turnEnd.id(), base.plusMillis(4)));
  }

  /** head 前进到新的 TURN_START：停止的父恢复执行后的持久形态，停止门禁不再成立。 */
  private void appendResumeTurnStart(UUID threadId) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          Instant base = headCreatedAt(tx, thread);
          Entry turnStart =
              insertEntry(
                  tx,
                  thread,
                  thread.headEntryId(),
                  new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, threadId),
                  base.plusMillis(1));
          tx.updateThread(thread.advanceHead(turnStart.id(), base.plusMillis(2)));
          return null;
        });
  }

  private static Instant headCreatedAt(HarnessStore.Transaction tx, ThreadState thread) {
    // Entry createdAt 必须沿路径严格递增：新 Entry 以当前 head 为基准，避免宿主时钟与数据库时钟混用。
    return tx.findEntry(thread.headEntryId()).orElseThrow().createdAt();
  }

  private static Entry insertEntry(
      HarnessStore.Transaction tx,
      ThreadState thread,
      UUID parentEntryId,
      EntryPayload payload,
      Instant createdAt) {
    Entry entry = new Entry(tx.nextId(), thread.sessionId(), parentEntryId, payload, createdAt);
    tx.insertEntry(entry);
    return entry;
  }

  private UUID headEntryId(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow().headEntryId());
  }

  private String taskOutcome(UUID invocationId) {
    return jdbc.queryForObject(
        "select outcome from harness_subagent_task where invocation_id = ?",
        String.class,
        invocationId);
  }

  private String taskStatus(UUID invocationId) {
    return jdbc.queryForObject(
        "select status from harness_subagent_task where invocation_id = ?",
        String.class,
        invocationId);
  }

  private int countOpenTasks(UUID parentThreadId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from harness_subagent_task where parent_thread_id = ? and status ="
                + " 'OPEN'",
            Integer.class,
            parentThreadId);
    return count == null ? 0 : count;
  }

  /** 父 Thread 上已入队的结果交付命令（CUSTOM_MESSAGE）。 */
  private List<ThreadCommand> deliveredCommands(UUID parentThreadId) {
    return store.transaction(
        tx ->
            tx.loadCommandsByThread(parentThreadId).stream()
                .filter(command -> command.payload() instanceof CustomMessageCommandPayload)
                .toList());
  }

  private static String messageText(ThreadCommand command) {
    CustomMessageCommandPayload payload = (CustomMessageCommandPayload) command.payload();
    StringBuilder text = new StringBuilder();
    for (AgentMessageContent content : payload.message().contents()) {
      if (content instanceof TextMessageContent value) {
        text.append(value.text());
      }
    }
    return text.toString();
  }

  private int count(String table, String column, UUID value) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from " + table + " where " + column + " = ?", Integer.class, value);
    return count == null ? 0 : count;
  }

  private static NewThreadCommand command(ThreadCommandPayload payload) {
    return new NewThreadCommand(
        payload, UUID.randomUUID(), ThreadCommandPayloadJsonCodec.requestHash(payload));
  }
}
