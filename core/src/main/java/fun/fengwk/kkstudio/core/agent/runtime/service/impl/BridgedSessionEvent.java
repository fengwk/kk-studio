package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.session.SessionEvent;

final class BridgedSessionEvent extends SessionEvent {

  private final String rawEventType;

  BridgedSessionEvent(String rawEventType) {
    this.rawEventType = rawEventType;
  }

  String getRawEventType() {
    return rawEventType;
  }
}
