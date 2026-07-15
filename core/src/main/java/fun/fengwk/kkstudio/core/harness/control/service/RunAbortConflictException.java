package fun.fengwk.kkstudio.core.harness.control.service;

/**
 * 表示 abort 调用在并发或竞争场景下命中不允许接受的状态。当前契约：
 *
 * <ul>
 *   <li>active path 取 hint 后锁 Run 与 Session，发现 Session.activeRunId 与 hint 不一致（被外部推进为另一 run）；
 *   <li>no-active path 仅锁 Session 后发现 Session.activeRunId 不再为 null。
 * </ul>
 *
 * 调用方应映射为 409 Conflict。
 */
public class RunAbortConflictException extends RuntimeException {

  public RunAbortConflictException(String message) {
    super(message);
  }
}
