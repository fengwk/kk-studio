package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.history.SystemPayload;
import fun.fengwk.kkstudio.harness.api.history.TurnCapabilities;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupProgress;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.spi.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次已接受 turn 的冻结计划：写入 system change、安装 READY invocation 并请求 MODEL Work。
 *
 * <p>{@link #install} 在 Runtime 已插入 TURN_START / 输入 Entry 并推进 Thread 之后、同一事务内执行；不开启新事务，也不做外部 I/O。
 */
final class BuiltinAcceptedTurn implements TurnResolution.Accepted {

  private final BuiltinStore store;
  private final ModelRequestSpec spec;
  private final Integer contextWindow;
  private final Integer maxOutputTokens;
  private final TurnCapabilities capabilities;
  private final ExecutionSelection executedSelection;
  private final SystemPayload systemChange;

  BuiltinAcceptedTurn(
      BuiltinStore store,
      ModelRequestSpec spec,
      int contextWindow,
      int maxOutputTokens,
      TurnCapabilities capabilities,
      ExecutionSelection executedSelection,
      SystemPayload systemChange) {
    this.store = Objects.requireNonNull(store, "store");
    this.spec = Objects.requireNonNull(spec, "spec");
    this.contextWindow = contextWindow;
    this.maxOutputTokens = maxOutputTokens;
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.executedSelection = Objects.requireNonNull(executedSelection, "executedSelection");
    this.systemChange = systemChange;
  }

  @Override
  public Integer contextWindow() {
    return contextWindow;
  }

  @Override
  public Integer maxOutputTokens() {
    return maxOutputTokens;
  }

  @Override
  public TurnCapabilities capabilities() {
    return capabilities;
  }

  @Override
  public SystemPayload systemChange() {
    return systemChange;
  }

  @Override
  public ExecutionSelection executedSelection() {
    return executedSelection;
  }

  @Override
  public void install(
      HarnessStore.Transaction tx, UUID threadId, UUID turnStartEntryId, UUID requestHeadEntryId) {
    UUID invocationId = tx.nextId();
    Instant now = tx.now();
    store
        .in(tx)
        .insertModelInvocation(
            new ModelInvocation(
                invocationId,
                threadId,
                turnStartEntryId,
                requestHeadEntryId,
                spec,
                ModelInvocationStatus.READY,
                0,
                null,
                null,
                null,
                null,
                List.of(),
                now,
                now,
                initialGroupProgress(spec),
                null));
    tx.work().request(new WorkTarget(BuiltinWorkType.MODEL, invocationId));
  }

  private static ModelGroupProgress initialGroupProgress(ModelRequestSpec spec) {
    ModelGroupExecution group = spec.groupExecution();
    return group == null
        ? null
        : new ModelGroupProgress(group.groupName(), group.groupVersion(), 0, 0);
  }
}
