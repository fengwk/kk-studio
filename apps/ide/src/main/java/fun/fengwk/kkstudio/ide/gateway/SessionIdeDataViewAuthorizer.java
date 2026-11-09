package fun.fengwk.kkstudio.ide.gateway;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeGenerationState;
import fun.fengwk.kkstudio.ide.IdeViewSnapshotStore;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeViewState;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 将 view 作用域 cookie 映射为数据入口授权；绑定事实只读取唯一权威快照。 */
@Component
public final class SessionIdeDataViewAuthorizer implements IdeDataViewAuthorizer {

  private final IdeBrowserSessionRegistry sessions;
  private final IdeViewSnapshotStore snapshots;
  private final IdeCommandTransport transport;

  public SessionIdeDataViewAuthorizer(
      IdeBrowserSessionRegistry sessions,
      IdeViewSnapshotStore snapshots,
      IdeCommandTransport transport) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  @Override
  public Optional<IdeDataViewAccess> authorize(
      EnvironmentId environmentId, UUID viewId, String cookieHeader) {
    if (cookieHeader == null) {
      return Optional.empty();
    }
    String name = IdeGatewayConstants.SESSION_COOKIE_NAME;
    String token = null;
    for (String part : cookieHeader.split(";")) {
      int equals = part.indexOf('=');
      if (equals >= 0 && name.equals(part.substring(0, equals).trim())) {
        if (token != null) {
          return Optional.empty();
        }
        token = part.substring(equals + 1).trim();
      }
    }
    Optional<IdeBrowserSessionRegistry.Session> session =
        sessions.lookup(token, environmentId, viewId.toString());
    if (session.isEmpty()) {
      return Optional.empty();
    }
    IdeBrowserSessionRegistry.Session value = session.get();
    if (IdeGenerationState.of(transport, value.identity()) == IdeGenerationState.REPLACED) {
      return Optional.empty();
    }
    return snapshots
        .snapshot(value.identity())
        .flatMap(snapshot -> snapshot.find(viewId))
        .filter(view -> view.state() == IdeViewState.OPEN)
        .filter(view -> view.bindingEpoch() == value.bindingEpoch())
        .filter(view -> view.workspacePath().equals(value.workspacePath()))
        .map(
            view ->
                new IdeDataViewAccess(
                    value.identity(), viewId, value.bindingEpoch(), Set.of(view.workspacePath())));
  }
}
