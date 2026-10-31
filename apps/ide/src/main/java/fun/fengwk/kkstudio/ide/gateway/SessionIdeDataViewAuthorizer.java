package fun.fengwk.kkstudio.ide.gateway;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeGenerationState;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 将真实 view 会话映射为数据入口授权；不新增凭据、授权缓存或续租。 */
@Component
public final class SessionIdeDataViewAuthorizer implements IdeDataViewAuthorizer {

  private final IdeBrowserSessionRegistry sessions;
  private final IdeCommandTransport transport;

  public SessionIdeDataViewAuthorizer(
      IdeBrowserSessionRegistry sessions, IdeCommandTransport transport) {
    this.sessions = Objects.requireNonNull(sessions, "sessions");
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
    return sessions
        .lookup(token, environmentId, viewId.toString())
        .filter(
            session ->
                IdeGenerationState.of(transport, session.identity()) != IdeGenerationState.REPLACED)
        .map(
            session ->
                new IdeDataViewAccess(
                    session.identity(),
                    viewId,
                    session.bindingEpoch(),
                    Set.of(session.workspacePath())));
  }
}
