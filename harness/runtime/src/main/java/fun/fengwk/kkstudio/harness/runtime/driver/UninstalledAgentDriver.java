package fun.fengwk.kkstudio.harness.runtime.driver;

import fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.spi.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCapabilities;
import fun.fengwk.kkstudio.harness.spi.driver.DriverCommit;
import fun.fengwk.kkstudio.harness.spi.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.spi.driver.TurnResolution;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.List;
import java.util.UUID;

/**
 * 未在当前构建中安装的 Driver 替身。
 *
 * <p>用于支撑已在历史中由其他 Driver 创建、但当前节点未装配对应 Driver 的 Thread。 这类 Thread 不能继续规划（resolveTurn
 * 确定性拒绝），但仍可停止、删除与查看。
 */
final class UninstalledAgentDriver implements AgentDriver {

  private static final DriverCapabilities CAPABILITIES = new DriverCapabilities(false, false);

  @Override
  public String id() {
    return "uninstalled";
  }

  @Override
  public DriverCapabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  public void validateSettings(String settingsJson) {
    throw new IllegalArgumentException("driver is not installed");
  }

  @Override
  public TurnResolution resolveTurn(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath candidatePath,
      CompactionPreparation preparation) {
    String driver = candidatePath.baseSettings().execution().driver();
    return new TurnResolution.Rejected(
        new AssistantError("PLANNING_FAILED", "driver is not installed: " + driver));
  }

  @Override
  public LockedDriverState readState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state) {
    return NoDriverState.INSTANCE;
  }

  @Override
  public LockedDriverState lockState(
      HarnessStore.Transaction tx, ThreadState thread, EntryCurrentState state) {
    return NoDriverState.INSTANCE;
  }

  @Override
  public DriverApplied applyResponse(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit) {
    throw new IllegalStateException("uninstalled driver cannot apply response");
  }

  @Override
  public void completeToolBatch(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      List<ToolInvocation> siblings,
      DriverCommit commit) {
    throw new IllegalStateException("uninstalled driver cannot complete tool batch");
  }

  @Override
  public DriverStopResult stop(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState state,
      DriverCommit commit,
      UUID stopRequestId) {
    return new DriverStopResult(null, List.of());
  }

  private static final class NoDriverState implements LockedDriverState {
    private static final NoDriverState INSTANCE = new NoDriverState();

    @Override
    public DriverActivity activity() {
      return DriverActivity.NONE;
    }

    @Override
    public UUID requestHeadEntryId() {
      return null;
    }

    @Override
    public UUID resultEntryId() {
      return null;
    }

    @Override
    public GenerationProjection projection() {
      return null;
    }
  }
}
