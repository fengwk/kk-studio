package fun.fengwk.kkstudio.core.agent.runtime.service.impl;

import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.projection.DefaultSessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;

import java.util.List;

final class CoreSessionEventMessageProjector implements SessionEventMessageProjector {

  private static final String USER_MESSAGE_EVENT_TYPE = "user_message";

  private final DefaultSessionEventMessageProjector delegate =
      new DefaultSessionEventMessageProjector();

  @Override
  public SessionEventProjection project(List<SessionEvent> branchEvents) {
    return project(branchEvents, false);
  }

  @Override
  public SessionEventProjection projectForRuntime(List<SessionEvent> branchEvents) {
    return project(branchEvents, true);
  }

  private SessionEventProjection project(
      List<SessionEvent> branchEvents, boolean runtimeProjection) {
    if (branchEvents == null) {
      return delegate.project(null);
    }
    List<SessionEvent> filteredEvents =
        branchEvents.stream()
            .filter(
                event ->
                    !(event instanceof BridgedSessionEvent bridgedEvent
                        && USER_MESSAGE_EVENT_TYPE.equals(bridgedEvent.getRawEventType())))
            .toList();
    return runtimeProjection
        ? delegate.projectForRuntime(filteredEvents)
        : delegate.project(filteredEvents);
  }
}
