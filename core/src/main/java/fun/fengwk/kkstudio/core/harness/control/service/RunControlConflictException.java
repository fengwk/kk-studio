package fun.fengwk.kkstudio.core.harness.control.service;

import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;

/** 当前 Session/Run 状态不允许接受指定 control。 */
public class RunControlConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final RunControlKind kind;

  public RunControlConflictException(RunControlKind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public RunControlKind kind() {
    return kind;
  }
}
