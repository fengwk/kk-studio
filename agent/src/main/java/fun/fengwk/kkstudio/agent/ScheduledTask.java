package fun.fengwk.kkstudio.agent;

/**
 * ScheduledTask 表示一次已注册的延迟任务。
 *
 * @author fengwk
 */
public interface ScheduledTask {

  /** 取消本次调度。 */
  void cancel();
}
