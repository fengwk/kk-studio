package fun.fengwk.kkstudio.agent.session.projection;

import fun.fengwk.kkstudio.agent.session.SessionEvent;

import java.util.List;

/**
 * 将 branch event 链投影为运行配置与消息上下文。
 *
 * <p>语义说明： - projector 是上下文重放扩展点。 - projector 负责恢复最新配置事实与可投影消息。
 *
 * @author fengwk
 */
public interface SessionEventMessageProjector {

  SessionEventProjection project(List<SessionEvent> branchEvents);

  default SessionEventProjection projectForRuntime(List<SessionEvent> branchEvents) {
    return project(branchEvents);
  }
}
