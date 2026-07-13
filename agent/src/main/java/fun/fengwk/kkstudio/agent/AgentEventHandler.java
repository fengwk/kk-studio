package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.session.SessionEvent;

/**
 * 当前回调用于向外发布 session tree 上新增的持久化 event 与未持久化的运行失败。
 *
 * <p>语义说明： - {@link #onEvent(SessionEvent)} 的参数表示新增的 session event。 - {@link #onFailure(Throwable)}
 * 仅在 Agent 无法继续当前 run 且没有对应终态 event 时调用。 - 调用方可基于这些回调构建展示、同步、索引与 run 状态收敛逻辑。
 *
 * @author fengwk
 */
public interface AgentEventHandler {

  void onEvent(SessionEvent event);

  /** 通知未持久化为 session event 的当前 run 失败。 */
  default void onFailure(Throwable error) {}
}
