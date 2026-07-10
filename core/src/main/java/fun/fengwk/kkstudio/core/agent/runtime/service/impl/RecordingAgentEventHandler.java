package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;

final class RecordingAgentEventHandler implements AgentEventHandler {

  private volatile SessionEventType lastEventType;
  private volatile boolean runFailed;

  @Override
  public void onEvent(SessionEvent event) {
    if (event != null) {
      lastEventType = event.getEventType();
    }
  }

  @Override
  public void onFailure(Throwable error) {
    runFailed = true;
  }

  boolean isTerminalFailure() {
    return runFailed
        || lastEventType == SessionEventType.assistant_error
        || lastEventType == SessionEventType.tool_error
        || lastEventType == SessionEventType.abort;
  }
}
