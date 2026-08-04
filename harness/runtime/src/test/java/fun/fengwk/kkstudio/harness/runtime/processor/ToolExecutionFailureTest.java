package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

class ToolExecutionFailureTest {

  private ScheduledExecutorService scheduler;

  @AfterEach
  void stopScheduler() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** partial fence 的 Store 异常是本地基础设施失败，不是 Gateway 协议错误：只 abandon，保留 RUNNING 供 lease 恢复为 UNKNOWN。 */
  @Test
  void partialStoreFailureAbandonsWithoutFalseProtocolFailure() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    scheduler = fixture.scheduler;
    ClaimedWork claim =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.toDispatched(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    FailNextHarnessStore store = new FailNextHarnessStore(fixture.store);
    ToolExecution execution = execution(fixture, store, claim);
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();
    assertEquals(ProcessResult.STARTED, execution.activate(handle));

    store.failNext();
    execution.onPartial(ToolProcessorTestSupport.partialResult("call-1"));

    assertEquals(
        ToolInvocationStatus.RUNNING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertNull(ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).error());
    assertTrue(fixture.sink.events.isEmpty());
    assertTrue(execution.abandoned());
    assertTrue(handle.isCancelled());
  }

  /** Started 后 markRunning 的 Store 异常必须 cancel handle，并保留 DISPATCHING 等待 lease 恢复。 */
  @Test
  void markRunningStoreFailureCancelsHandleWithoutDurableWrite() {
    ToolProcessorTestSupport.Fixture fixture = ToolProcessorTestSupport.fixture();
    scheduler = fixture.scheduler;
    ClaimedWork claim =
        ToolProcessorTestSupport.claim(
            fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    ToolProcessorTestSupport.toDispatched(
        fixture.store, fixture.toolInvocationId, ToolProcessorTestSupport.NOW);
    FailNextHarnessStore store = new FailNextHarnessStore(fixture.store);
    ToolExecution execution = execution(fixture, store, claim);
    ToolProcessorTestSupport.FakeHandle handle = new ToolProcessorTestSupport.FakeHandle();

    store.failNext();
    assertEquals(ProcessResult.LOST_OWNERSHIP, execution.activate(handle));

    assertEquals(
        ToolInvocationStatus.DISPATCHING,
        ToolProcessorTestSupport.tool(fixture.store, fixture.toolInvocationId).status());
    assertTrue(execution.abandoned());
    assertTrue(handle.isCancelled());
  }

  private ToolExecution execution(
      ToolProcessorTestSupport.Fixture fixture, HarnessStore store, ClaimedWork claim) {
    return new ToolExecution(
        store,
        fixture.sink,
        claim,
        fixture.baseline.threadId(),
        1,
        fixture.request,
        new ToolProcessorConfig(
            ToolProcessorTestSupport.LEASE_CONFIG,
            ToolProcessorTestSupport.NO_RETRY,
            ToolProcessorTestSupport.PREFLIGHT_FAILURE_DELAY,
            ToolProcessorTestSupport.BUSY_FALLBACK_DELAY),
        fixture.clock,
        fixture.scheduler,
        ignored -> {});
  }

  private static final class FailNextHarnessStore implements HarnessStore {

    private final HarnessStore delegate;
    private final AtomicBoolean failNext = new AtomicBoolean();

    private FailNextHarnessStore(HarnessStore delegate) {
      this.delegate = delegate;
    }

    void failNext() {
      failNext.set(true);
    }

    @Override
    public <T> T transaction(Function<Transaction, T> callback) {
      if (failNext.compareAndSet(true, false)) {
        throw new IllegalStateException("injected Store failure");
      }
      return delegate.transaction(callback);
    }
  }
}
