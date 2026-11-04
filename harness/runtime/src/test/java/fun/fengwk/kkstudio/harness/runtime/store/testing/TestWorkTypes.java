package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

/** Runtime 测试使用的 Driver Work 类型；生产类型由各 Driver 自己定义。 */
public final class TestWorkTypes {

  /** 与 Builtin 生产类型同形：rank 位于 THREAD 与 TOOL 之间。 */
  public static final WorkTargetType MODEL = WorkTargetType.driver("MODEL", 1);

  private TestWorkTypes() {}
}
