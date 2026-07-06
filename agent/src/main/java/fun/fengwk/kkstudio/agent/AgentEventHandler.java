package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.session.SessionEvent;

/**
 * 当前回调用于向外发布 session tree 上新增的持久化 event。
 *
 * <p>语义说明： - 回调参数表示新增的 session event。 - 调用方可基于该回调构建展示、同步或索引逻辑。
 *
 * @author fengwk
 */
public interface AgentEventHandler {

  void onEvent(SessionEvent event);
}
