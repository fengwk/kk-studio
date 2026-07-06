package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.AgentEventHandler;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;

final class RecordingAgentEventHandler implements AgentEventHandler {

  private SessionEventType lastEventType;

  @Override
  public void onEvent(SessionEvent event) {
    if (event != null) {
      lastEventType = event.getEventType();
    }
  }

  boolean isTerminalFailure() {
    return lastEventType == SessionEventType.assistant_error
        || lastEventType == SessionEventType.tool_error
        || lastEventType == SessionEventType.abort;
  }
}
