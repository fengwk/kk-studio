package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.port.BuiltinWorkType;
import fun.fengwk.kkstudio.harness.builtin.port.store.BuiltinStore;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.spi.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;

import java.util.Optional;
import java.util.UUID;

/**
 * Builtin 的 Driver 私有状态：当前 open Turn 的 generation 投影。
 *
 * <p>Runtime 只通过 {@link LockedDriverState} 读取 activity / projection / work target，不解读模型行本身。包内 Apply
 * / Stop 路径通过 {@link #model()} 读取冻结请求与终态事实。
 */
final class BuiltinState implements LockedDriverState {

  private final ModelInvocation model;
  private final DriverActivity activity;
  private final GenerationProjection projection;

  BuiltinState(ModelInvocation model, DriverActivity activity) {
    this.model = model;
    this.activity = activity;
    this.projection = BuiltinGenerationProjection.of(model);
  }

  /** 只读投影本 Thread 当前 open turn 的 generation；没有 open turn 或没有 generation 时为无状态。 */
  static LockedDriverState read(
      BuiltinStore store,
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryCurrentState state) {
    return find(store, tx, thread, state)
        .<LockedDriverState>map(model -> project(model, state))
        .orElse(NoDriverState.INSTANCE);
  }

  /** 锁定本 Thread 当前 open turn 的 generation；行消失即为所有权竞争失败，显式抛错。 */
  static LockedDriverState lock(
      BuiltinStore store,
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryCurrentState state) {
    Optional<ModelInvocation> found = find(store, tx, thread, state);
    if (found.isEmpty()) {
      return NoDriverState.INSTANCE;
    }
    ModelInvocation model =
        store
            .in(tx)
            .lockModelInvocation(found.get().id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "model "
                            + found.get().id()
                            + " could not be locked for thread "
                            + thread.id()));
    return project(model, state);
  }

  private static Optional<ModelInvocation> find(
      BuiltinStore store,
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryCurrentState state) {
    Optional<Entry> openTurn = state.openTurnStart();
    if (openTurn.isEmpty()) {
      return Optional.empty();
    }
    return store.in(tx).findModelInvocationByTurn(thread.id(), openTurn.get().id());
  }

  private static BuiltinState project(ModelInvocation model, EntryCurrentState state) {
    DriverActivity activity = DriverActivity.NONE;
    if (model.resultEntryId() == null && model.requestHeadEntryId().equals(state.head().id())) {
      activity =
          model.status().isTerminal() ? DriverActivity.RESPONSE_PENDING : DriverActivity.ACTIVE;
    }
    return new BuiltinState(model, activity);
  }

  ModelInvocation model() {
    return model;
  }

  @Override
  public DriverActivity activity() {
    return activity;
  }

  @Override
  public UUID requestHeadEntryId() {
    return model.requestHeadEntryId();
  }

  @Override
  public UUID resultEntryId() {
    return model.resultEntryId();
  }

  @Override
  public GenerationProjection projection() {
    return projection;
  }

  @Override
  public UUID generationId() {
    return model.id();
  }

  @Override
  public WorkTarget workTarget() {
    // Stop 据此锁定并删除本 generation 的 MODEL Work；tool 阶段 generation 已 attached、
    // activity 不是 ACTIVE，但 Work 仍归属该 generation。
    return new WorkTarget(BuiltinWorkType.MODEL, model.id());
  }
}
