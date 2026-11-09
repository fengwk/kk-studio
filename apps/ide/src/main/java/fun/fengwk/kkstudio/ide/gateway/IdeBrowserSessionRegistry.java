package fun.fengwk.kkstudio.ide.gateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * IDE 浏览器 cookie 会话登记。
 *
 * <p>cookie 严格绑定 Environment + Daemon 实例 + viewId + bindingEpoch，并只通过 view 作用域 Path 下发。本类只管理一次性
 * bootstrap 之后的 cookie 生命周期；视图是否存在、是否仍属于该 epoch 与 workspace 完全由 {@link
 * fun.fengwk.kkstudio.ide.IdeViewSnapshotStore} 的唯一权威快照裁决。
 */
@Component
public final class IdeBrowserSessionRegistry {

  /** 一个浏览器 IDE cookie 会话；授权仍需匹配权威快照。 */
  public record Session(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {

    public Session {
      identity = Objects.requireNonNull(identity, "identity");
      viewId = Objects.requireNonNull(viewId, "viewId");
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
      workspacePath = Objects.requireNonNull(workspacePath, "workspacePath");
    }

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }
  }

  private static final int TOKEN_BYTES = 32;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;
  private final Object lock = new Object();
  private final Map<String, SessionState> sessions = new HashMap<>();
  private final Map<SessionKey, String> tokenBySessionKey = new HashMap<>();

  @Autowired
  public IdeBrowserSessionRegistry() {
    this(new SecureRandom(), Clock.systemUTC(), IdeGatewayConstants.DEFAULT_SESSION_TTL);
  }

  public IdeBrowserSessionRegistry(SecureRandom random, Clock clock, Duration sessionTtl) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
  }

  /** 为已由权威快照确认的绑定签发/复用 view 作用域 cookie。 */
  public String open(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    if (bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be positive");
    }
    synchronized (lock) {
      SessionKey key = new SessionKey(identity, viewId, bindingEpoch);
      Instant now = clock.instant();
      String existingToken = tokenBySessionKey.get(key);
      SessionState existing = existingToken == null ? null : sessions.get(existingToken);
      if (existing != null
          && existing.expiresAt.isAfter(now)
          && existing.workspacePath.equals(workspacePath)) {
        existing.expiresAt = now.plus(sessionTtl);
        return existingToken;
      }
      if (existingToken != null) {
        removeSession(existingToken);
      }
      String token = newToken();
      SessionState session =
          new SessionState(identity, viewId, bindingEpoch, workspacePath, now.plus(sessionTtl));
      sessions.put(token, session);
      tokenBySessionKey.put(key, token);
      return token;
    }
  }

  public Optional<Session> lookup(String cookieValue, EnvironmentId environmentId, String viewId) {
    if (cookieValue == null || cookieValue.isBlank()) {
      return Optional.empty();
    }
    synchronized (lock) {
      SessionState state = sessions.get(cookieValue);
      if (state == null) {
        return Optional.empty();
      }
      Instant now = clock.instant();
      if (!state.expiresAt.isAfter(now)) {
        removeSession(cookieValue);
        return Optional.empty();
      }
      if (!state.identity.environmentId().equals(environmentId) || !state.viewId.equals(viewId)) {
        return Optional.empty();
      }
      return Optional.of(
          new Session(state.identity, state.viewId, state.bindingEpoch, state.workspacePath));
    }
  }

  /** 只删除该 Daemon 进程该视图该 epoch 的 cookie，不触碰新绑定。 */
  public void remove(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    synchronized (lock) {
      sessions
          .entrySet()
          .removeIf(
              entry -> {
                SessionState session = entry.getValue();
                boolean remove =
                    session.identity.equals(identity)
                        && session.viewId.equals(viewId)
                        && session.bindingEpoch == bindingEpoch;
                if (remove) {
                  tokenBySessionKey.remove(session.key());
                }
                return remove;
              });
    }
  }

  private void removeSession(String token) {
    SessionState removed = sessions.remove(token);
    if (removed != null) {
      tokenBySessionKey.remove(removed.key());
    }
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private record SessionKey(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {}

  private static final class SessionState {
    private final DaemonConnectionIdentity identity;
    private final String viewId;
    private final long bindingEpoch;
    private final String workspacePath;
    private Instant expiresAt;

    private SessionState(
        DaemonConnectionIdentity identity,
        String viewId,
        long bindingEpoch,
        String workspacePath,
        Instant expiresAt) {
      this.identity = identity;
      this.viewId = viewId;
      this.bindingEpoch = bindingEpoch;
      this.workspacePath = workspacePath;
      this.expiresAt = expiresAt;
    }

    private SessionKey key() {
      return new SessionKey(identity, viewId, bindingEpoch);
    }
  }
}
