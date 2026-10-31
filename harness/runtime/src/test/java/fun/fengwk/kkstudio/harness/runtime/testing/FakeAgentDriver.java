package fun.fengwk.kkstudio.harness.runtime.testing;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverCommit;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.runtime.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runtime 内核测试用的最小确定性 Driver。
 *
 * <p>默认行为是“没有 Driver 私有状态”：turn resolution 返回 {@code null}、自动压缩返回 {@code null}、read/lock 返回 {@link
 * DriverActivity#NONE}、stop 不追加 barrier。需要 Driver 参与的用例可配置 turn resolution、活动投影、响应物化结果与停止结果。
 *
 * <p>私有状态只存在内存，不参与 Store 事务提交/回滚；这正是 runtime 内核测试需要的边界：Runtime 只能观察 {@link LockedDriverState}
 * 中立投影，不能读取 Driver 私有实现类型。需要验证 Driver 私有行与核心事务同快照/回滚的测试应放到 builtin 的 typed store 测试中。
 */
public final class FakeAgentDriver implements AgentDriver {

  private static final LockedDriverState NONE_STATE =
      new FakeLockedDriverState(DriverActivity.NONE, null, null, null, null);

  private final Supplier<TurnResolution> turnResolution;
  private final Supplier<CompactionPreparation> automaticCompaction;
  private LockedDriverState state = NONE_STATE;
  private DriverApplied applied;
  private DriverStopResult stopResult = new DriverStopResult(null, List.of());

  public FakeAgentDriver() {
    this(() -> null, () -> null);
  }

  public FakeAgentDriver(Supplier<TurnResolution> turnResolution) {
    this(turnResolution, () -> null);
  }

  public FakeAgentDriver(
      Supplier<TurnResolution> turnResolution,
      Supplier<CompactionPreparation> automaticCompaction) {
    this.turnResolution = Objects.requireNonNull(turnResolution, "turnResolution");
    this.automaticCompaction = Objects.requireNonNull(automaticCompaction, "automaticCompaction");
  }

  /** 固定返回同一个 turn resolution 的便捷构造。 */
  public static FakeAgentDriver resolving(TurnResolution resolution) {
    Objects.requireNonNull(resolution, "resolution");
    return new FakeAgentDriver(() -> resolution);
  }

  /** 配置 read/lock 返回的活动投影。 */
  public FakeAgentDriver withState(LockedDriverState state) {
    this.state = Objects.requireNonNull(state, "state");
    return this;
  }

  /** 配置下一次 {@link #applyResponse} 返回的公共物化事实。 */
  public FakeAgentDriver withApplied(DriverApplied applied) {
    this.applied = Objects.requireNonNull(applied, "applied");
    return this;
  }

  /** 配置 stop 返回的 barrier 与本地执行身份。 */
  public FakeAgentDriver withStopResult(DriverStopResult stopResult) {
    this.stopResult = Objects.requireNonNull(stopResult, "stopResult");
    return this;
  }

  @Override
  public TurnResolution resolveTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath candidatePath,
      CompactionPreparation preparation) {
    return turnResolution.get();
  }

  @Override
  public LockedDriverState readState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState current) {
    return state;
  }

  @Override
  public LockedDriverState lockState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState current) {
    return state;
  }

  @Override
  public DriverApplied applyResponse(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit) {
    if (applied == null) {
      throw new UnsupportedOperationException("FakeAgentDriver has no response to apply");
    }
    return applied;
  }

  @Override
  public void completeToolBatch(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      List<ToolInvocation> siblings,
      DriverCommit commit) {}

  @Override
  public CompactionPreparation prepareAutomaticCompaction(
      ThreadState thread, EntryPath path, CompactionConfig config, boolean hasQueuedMessage) {
    return automaticCompaction.get();
  }

  @Override
  public DriverStopResult stop(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit,
      UUID stopRequestId) {
    return stopResult;
  }

  /** 可直接构造生成结果、工具调用、失败与本地执行身份的中立投影。 */
  public record FakeLockedDriverState(
      DriverActivity activity,
      UUID requestHeadEntryId,
      UUID resultEntryId,
      GenerationProjection projection,
      UUID executionId)
      implements LockedDriverState {

    public FakeLockedDriverState {
      activity = Objects.requireNonNull(activity, "activity");
    }

    @Override
    public UUID generationId() {
      return projection == null ? null : projection.generationId();
    }
  }
}
