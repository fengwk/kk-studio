package fun.fengwk.kkstudio.harness.builtin.port;

import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;

/** Builtin Driver 自己的 Work 类型；其他 Driver 用不同的 value/rank 注册自己的类型。 */
public final class BuiltinWorkType {

  /**
   * Builtin 模型执行 Work：以 {@code ModelInvocation} 身份为键，不带环境亲和性。
   *
   * <p>rank 位于内核 THREAD 与 TOOL 之间，保持既有 {@code THREAD -> MODEL -> TOOL} 加锁顺序。
   */
  public static final WorkTargetType MODEL = WorkTargetType.driver("MODEL", 1);

  private BuiltinWorkType() {}
}
