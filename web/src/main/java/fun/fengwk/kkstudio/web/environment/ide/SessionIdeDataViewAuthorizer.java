package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCommandTransport;
import fun.fengwk.kkstudio.ide.IdeGenerationState;

import java.util.*;

/** 将真实 view 会话映射为数据入口授权；不新增凭据、授权缓存或续租。 */
@Component
public final class SessionIdeDataViewAuthorizer implements IdeDataViewAuthorizer {
  private final IdeBrowserSessionRegistry sessions;
  private final IdeGatewayProperties properties;
  private final IdeCommandTransport transport;

  public SessionIdeDataViewAuthorizer(
      IdeBrowserSessionRegistry sessions,
      IdeGatewayProperties properties,
      IdeCommandTransport transport) {
    this.sessions = Objects.requireNonNull(sessions);
    this.properties = Objects.requireNonNull(properties);
    this.transport = Objects.requireNonNull(transport);
  }

  @Override
  public Optional<IdeDataViewAccess> authorize(
      EnvironmentId environmentId, UUID viewId, String cookieHeader) {
    if (cookieHeader == null) {
      return Optional.empty();
    }
    String name = properties.requireSessionCookieName();
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
