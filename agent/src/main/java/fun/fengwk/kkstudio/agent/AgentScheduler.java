package fun.fengwk.kkstudio.agent;

import java.time.Duration;

/**
 * AgentScheduler 负责注册一次性的异步延迟触发。
 *
 * @author fengwk
 */
public interface AgentScheduler {

  /** 在指定延迟后调度一次任务。 */
  ScheduledTask schedule(Duration delay, Runnable task);
}
