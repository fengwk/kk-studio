package fun.fengwk.kkstudio.harness.builtin.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.runtime.processor.ToolProcessorTestSupport.Fixture;
import fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreCallProbe;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessorConfig;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.ProcessResult;
import fun.fengwk.kkstudio.harness.spi.work.Work;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * Tool admission 的冻结执行上下文事实：会话、执行根与其会话、该调用的 assistant 入口、assistant 分支视图与 TURN_START 冻结的消费者媒体能力都来自
 * Processor 已锁定的 Thread 与该调用的 assistant path，平台无需为此查询 Harness 存储。
 *
 * <p>同时钉住两条恢复语义：远端重新挂接只连接既有远端执行、不读取这些事实；READY prepare 内读取这些事实失败时事务完整回滚并按 admission 失败路径有界重排。
 */
class ToolProcessorExecutionScopeTest {

  /** 执行根上的 Tool：会话就是自身会话，执行根指向自身，媒体能力来自该 Turn 冻结的 TURN_START。 */
  @Test
  void rootExecutionScopeCarriesFrozenThreadSessionRootAndMediaCapabilities() {
    Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toApproved(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.succeeded("call-1"));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolGateway.ExecutionScope scope = fixture.gateway.executions.get(0).scope();
    ThreadState thread =
        ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId());
    assertEquals(thread.id(), scope.threadId());
    assertEquals(thread.sessionId(), scope.sessionId());
    assertEquals(thread.rootThreadId(), scope.rootThreadId());
    assertEquals(thread.sessionId(), scope.rootSessionId());
    // assistant 入口就是该 Tool 调用冻结的真实调用位置，平台据此装配执行上下文而不查询 Harness 存储。
    assertEquals(
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).assistantEntryId(),
        scope.assistantEntryId());
    // 媒体能力唯一来源是该调用 TURN_START 的冻结快照，而不是实时模型。
    assertTrue(scope.mediaCapabilities().declares(ModelInputModality.IMAGE));
    assertTrue(scope.mediaCapabilities().acceptsProtocolPosition(ModelInputModality.IMAGE));
  }

  /** 子代理 Tool：执行根与其会话来自真实根 Thread 行，与本 Thread 自身的会话不同，绝不按当前 Thread 合成。 */
  @Test
  void childExecutionScopeUsesRealRootThreadAndRootSession() {
    Fixture fixture = ToolProcessorTestSupport.childFixture(false, 1);
    fixture.gateway.queuePreflightAllow();
    fixture.gateway.queueRun(progress -> ToolProcessorTestSupport.succeeded("call-1"));

    assertEquals(
        ProcessResult.STARTED,
        fixture.processor.process(
            ToolProcessorTestSupport.claim(
                fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));

    ToolGateway.ExecutionScope scope = fixture.gateway.executions.get(0).scope();
    ThreadState child = ToolProcessorTestSupport.thread(fixture.store, fixture.baseline.threadId());
    ThreadState root =
        fixture.store.transaction(tx -> tx.threads().find(child.rootThreadId()).orElseThrow());
    assertEquals(child.id(), scope.threadId());
    assertEquals(child.sessionId(), scope.sessionId());
    assertEquals(root.id(), scope.rootThreadId());
    assertEquals(root.sessionId(), scope.rootSessionId());
    assertNotEquals(
        scope.sessionId(),
        scope.rootSessionId(),
        "subagent runs in its own session while the execution root keeps the origin session");
    assertEquals(
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).assistantEntryId(),
        scope.assistantEntryId(),
        "child scope keeps the call's own assistant entry, not the root thread's");
    assertNotNull(scope.mediaCapabilities());
  }

  /**
   * 远端重新挂接只连接既有远端执行：挂接描述只带所属 Thread id，prepare 事务不读取分支视图、TURN_START 或执行根，绝不因为无关的上下文事实而 卡住恢复；attempt
   * 不变、绝不重发 INVOKE。子代理 Thread 复用同一路径：若仍按 admission 那样冻结上下文事实，这里的执行根读取就会出现在事务里。
   */
  @Test
  void reattachAttachesExistingRemoteExecutionWithoutReadingScopeFacts() throws Exception {
    ScheduledExecutorService scheduler = ToolProcessorTestSupport.newScheduler();
    Fixture fixture =
        new Fixture(
            ToolProcessorTestSupport.NO_RETRY,
            false,
            1,
            scheduler,
            ToolProcessorTestSupport.remoteScenario(ToolSideEffect.READ_ONLY),
            true);
    ToolProcessorTestSupport.toRunning(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    UUID oldLease = UUID.randomUUID();
    ExecutionExitFact oldToolFact =
        ToolProcessorTestSupport.registerOldToolExit(
            fixture.store, fixture.baseline.threadId(), fixture.toolInvocationId, 1, oldLease);
    ToolProcessorTestSupport.registerRemoteExit(
        fixture.store,
        fixture.baseline.threadId(),
        fixture.toolInvocationId,
        1,
        oldLease,
        oldToolFact.executionId(),
        ToolProcessorTestSupport.ENV_ID);
    ToolProcessorTestSupport.claim(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    fixture.clock.advance(Duration.ofSeconds(61));
    ClaimedWork recovered =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, fixture.clock.instant());

    ProbeStore probe = new ProbeStore(fixture.store, (call, next) -> next.invoke());
    try (ToolProcessor processor = processor(probe, fixture)) {
      fixture.gateway.queueReattachRun(progress -> ToolProcessorTestSupport.succeeded("call-1"));

      assertEquals(ProcessResult.STARTED, processor.process(recovered));
      ToolProcessorTestSupport.awaitTrue(
          () ->
              ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status()
                  == ToolInvocationStatus.SUCCEEDED,
          Duration.ofSeconds(5));
      ToolProcessorTestSupport.awaitTrue(
          () -> !processor.hasActiveExecution(), Duration.ofSeconds(5));
    } finally {
      scheduler.shutdownNow();
    }

    ToolGateway.ReattachExecution reattach = fixture.gateway.reattachExecutions.getFirst();
    assertEquals(fixture.baseline.threadId(), reattach.threadId());
    assertEquals(1, fixture.gateway.reattachCalls, "must reattach once");
    assertEquals(0, fixture.gateway.admitCalls, "must never admit/invoke again");
    assertEquals(
        1,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).attempt(),
        "attempt must remain unchanged");
    List<String> prepare = probe.transactionPerforming("exits.loadRemote");
    assertTrue(
        prepare.stream().noneMatch(operation -> operation.startsWith("entries.")),
        "reattach must not read branch or TURN_START entries: " + prepare);
    assertFalse(
        prepare.stream().anyMatch(operation -> operation.equals("threads.find")),
        "reattach must not read the execution root thread: " + prepare);
  }

  /** 只有新增 scope 读取的失败被重排：其它 prepare 读取失败保持原样抛出，工具不执行也不改状态。 */
  @Test
  void otherPrepareReadFailurePropagatesUnchanged() {
    Fixture fixture = ToolProcessorTestSupport.fixture();
    ToolProcessorTestSupport.toApproved(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    Work before = ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId);
    ProbeStore probe =
        new ProbeStore(
            fixture.store,
            (call, next) -> {
              if ("tools.find".equals(call.operation())) {
                throw new IllegalStateException("injected tool read failure");
              }
              return next.invoke();
            });

    try (ToolProcessor processor = processor(probe, fixture)) {
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () ->
                  processor.process(
                      ToolProcessorTestSupport.claim(
                          fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW)));
      assertEquals("injected tool read failure", failure.getMessage());
      assertFalse(processor.hasActiveExecution());
    }

    assertEquals(
        ToolInvocationStatus.READY,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertEquals(
        before.availableAt(),
        ToolProcessorTestSupport.toolWork(fixture.store, fixture.toolInvocationId).availableAt(),
        "non-scope prepare failure must not be rescheduled");
    assertEquals(0, fixture.gateway.admitCalls);
  }

  /** 用探针 Store 构造与被替换 processor 共享 gateway / sink / clock / config 的 processor。 */
  private static ToolProcessor processor(HarnessStore store, Fixture fixture) {
    return new ToolProcessor(
        store,
        fixture.gateway,
        fixture.sink,
        new ToolProcessorConfig(
            ToolProcessorTestSupport.LEASE_CONFIG_PROVIDER,
            () -> ToolProcessorTestSupport.NO_RETRY,
            ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY_PROVIDER,
            ToolProcessorTestSupport.BUSY_FALLBACK_DELAY_PROVIDER,
            ToolProcessorTestSupport.REATTACH_WINDOW),
        fixture.clock,
        fixture.scheduler,
        Runnable::run,
        ToolProcessorTestSupport.testLifecycle(store, fixture.clock),
        TestExecutionExitConfirmer.create(store, fixture.clock),
        ToolProcessorTestSupport.STABLE_MATERIALIZER);
  }

  /**
   * 事务探针 Store：记录每个 store 事务内按真实聚合路径发生的调用，并允许对指定调用注入故障。
   *
   * <p>只包装事务句柄，读写仍落在同一个 {@link InMemoryBuiltinHarnessStore} 上，因此回滚、锁序与并发语义不变。
   */
  private static final class ProbeStore implements HarnessStore {

    private final InMemoryBuiltinHarnessStore delegate;
    private final StoreCallProbe.Handler handler;
    private final List<List<String>> transactions = new CopyOnWriteArrayList<>();

    private ProbeStore(InMemoryBuiltinHarnessStore delegate, StoreCallProbe.Handler handler) {
      this.delegate = delegate;
      this.handler = handler;
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      return delegate.transaction(
          tx -> {
            List<String> operations = new CopyOnWriteArrayList<>();
            transactions.add(operations);
            return callback.apply(
                StoreCallProbe.wrap(
                    tx,
                    (call, next) -> {
                      operations.add(call.operation());
                      return handler.handle(call, next);
                    }));
          });
    }

    @Override
    public void afterCommit(Runnable action) {
      delegate.afterCommit(action);
    }

    @Override
    public void assertNoAmbientTransaction() {
      delegate.assertNoAmbientTransaction();
    }

    /** 第一个执行了给定操作的 store 事务调用序列；用于断言某个 prepare 事务读了什么。 */
    private List<String> transactionPerforming(String operation) {
      return transactions.stream()
          .filter(operations -> operations.contains(operation))
          .findFirst()
          .orElseThrow(() -> new AssertionError("no store transaction performed " + operation));
    }
  }
}
