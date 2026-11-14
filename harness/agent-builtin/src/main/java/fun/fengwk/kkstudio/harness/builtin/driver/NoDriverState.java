package fun.fengwk.kkstudio.harness.builtin.driver;

import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.spi.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState;

import java.util.UUID;

/** 本 Thread 当前 open turn 没有 Builtin generation 时的中立状态：不参与任何 generation 生命周期。 */
final class NoDriverState implements LockedDriverState {

  static final NoDriverState INSTANCE = new NoDriverState();

  private NoDriverState() {}

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
