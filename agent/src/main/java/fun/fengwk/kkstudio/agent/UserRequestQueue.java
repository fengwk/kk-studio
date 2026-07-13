package fun.fengwk.kkstudio.agent;

import java.util.List;

/**
 * UserRequestQueue 表示 Agent 的输入缓冲区。
 *
 * <p>语义说明：
 *
 * <ul>
 *   <li>submit 动作先进入 queue。
 *   <li>queue 中的请求在 drain 时会被整理进后续 assistant_start 事件的输入负载。
 *   <li>实现必须线程安全：submit 可由外部调用线程执行，pollAll 和 isEmpty 由 Agent signal drain 调用。
 *   <li>与 pollAll 并发提交的请求可归入本次或下一次收割。
 * </ul>
 *
 * @author fengwk
 */
public interface UserRequestQueue {

  /** 提交用户请求。 */
  void submit(UserRequest userRequest);

  /** 拉取当前队列中的全部请求。 */
  List<UserRequest> pollAll();

  /** 当前队列是否为空。 */
  boolean isEmpty();
}
