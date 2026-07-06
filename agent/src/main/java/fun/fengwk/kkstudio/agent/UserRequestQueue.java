package fun.fengwk.kkstudio.agent;

import java.util.List;

import java.util.List;

/**
 * UserRequestQueue 表示 Agent 的输入缓冲区。
 *
 * <p>语义说明： - submit 动作先进入 queue。 - queue 中的请求在 drain 时会被整理进后续 assistant_start 事件的输入负载。 - queue
 * 承载运行时控制面的输入聚合职责。
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
