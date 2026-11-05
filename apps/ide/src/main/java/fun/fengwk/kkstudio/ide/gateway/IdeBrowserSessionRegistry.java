package fun.fengwk.kkstudio.ide.gateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeViewAuthorizationListener;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * IDE 浏览器会话与受管 workspace 授权登记。
 *
 * <p>cookie 严格绑定 Environment + Daemon 实例 + viewId + bindingEpoch，并只应通过 view 作用域 Path 下发。
 */
@Component
public final class IdeBrowserSessionRegistry implements IdeViewAuthorizationListener {

  /** 一个浏览器 IDE 会话：只允许该 view 的该 epoch 使用对应 workspace。 */
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
  static final int MAX_VIEWS_PER_GENERATION = 64;
  static final int MAX_GENERATIONS = 4096;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;
  private final Object lock = new Object();

  private final Map<Generation, GenerationState> generations = new HashMap<>();
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

  public String open(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    synchronized (lock) {
      Generation generation = new Generation(identity);
      GenerationState state = generations.get(generation);
      AuthorizedView authorized = state == null ? null : state.views.get(viewId);
      if (authorized == null
          || authorized.bindingEpoch != bindingEpoch
          || !authorized.workspacePath.equals(workspacePath)) {
        throw new IllegalStateException("bootstrap ticket does not match an open IDE view");
      }
      SessionKey key = new SessionKey(identity.environmentId(), viewId, bindingEpoch);
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
      GenerationState generation = generations.get(new Generation(state.identity));
      AuthorizedView authorized = generation == null ? null : generation.views.get(viewId);
      if (authorized == null
          || authorized.bindingEpoch != state.bindingEpoch
          || !authorized.workspacePath.equals(state.workspacePath)) {
        removeSession(cookieValue);
        return Optional.empty();
      }
      return Optional.of(
          new Session(state.identity, state.viewId, state.bindingEpoch, state.workspacePath));
    }
  }

  @Override
  public void ensureViewCapacity(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    synchronized (lock) {
      for (Map.Entry<Generation, GenerationState> entry : generations.entrySet()) {
        if (!entry.getKey().identity.environmentId().equals(environmentId)) {
          continue;
        }
        if (entry.getValue().views.size() >= MAX_VIEWS_PER_GENERATION) {
          throw new IllegalStateException("too many open IDE views for this environment");
        }
      }
    }
  }

  @Override
  public void viewOpened(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    if (bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be positive");
    }
    synchronized (lock) {
      Generation generation = new Generation(identity);
      GenerationState state = generations.get(generation);
      if (state == null) {
        if (generations.size() >= MAX_GENERATIONS) {
          throw new IllegalStateException("too many concurrent IDE browser generations");
        }
        state = new GenerationState();
        generations.put(generation, state);
      }
      if (!state.views.containsKey(viewId) && state.views.size() >= MAX_VIEWS_PER_GENERATION) {
        throw new IllegalStateException("too many open IDE views in this generation");
      }
      AuthorizedView previous =
          state.views.put(viewId, new AuthorizedView(bindingEpoch, workspacePath));
      if (previous != null
          && previous.bindingEpoch == bindingEpoch
          && !previous.workspacePath.equals(workspacePath)) {
        removeAllSessionsForView(identity.environmentId(), viewId);
      } else {
        removeSessionsForView(identity.environmentId(), viewId, bindingEpoch);
      }
    }
  }

  @Override
  public void viewClosed(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    synchronized (lock) {
      GenerationState state = generations.get(new Generation(identity));
      if (state == null) {
        return;
      }
      AuthorizedView authorized = state.views.get(viewId);
      if (authorized == null || authorized.bindingEpoch != bindingEpoch) {
        return;
      }
      state.views.remove(viewId);
      removeAllSessionsForView(identity.environmentId(), viewId);
    }
  }

  public int size() {
    synchronized (lock) {
      return sessions.size();
    }
  }

  private void removeSessionsForView(
      EnvironmentId environmentId, String viewId, long keepBindingEpoch) {
    sessions
        .entrySet()
        .removeIf(
            entry -> {
              SessionState session = entry.getValue();
              boolean remove =
                  session.identity.environmentId().equals(environmentId)
                      && session.viewId.equals(viewId)
                      && session.bindingEpoch != keepBindingEpoch;
              if (remove) {
                tokenBySessionKey.remove(session.key());
              }
              return remove;
            });
  }

  private void removeAllSessionsForView(EnvironmentId environmentId, String viewId) {
    sessions
        .entrySet()
        .removeIf(
            entry -> {
              SessionState session = entry.getValue();
              boolean remove =
                  session.identity.environmentId().equals(environmentId)
                      && session.viewId.equals(viewId);
              if (remove) {
                tokenBySessionKey.remove(session.key());
              }
              return remove;
            });
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

  private static final class GenerationState {
    private final Map<String, AuthorizedView> views = new LinkedHashMap<>();
  }

  private record Generation(DaemonConnectionIdentity identity) {}

  private record AuthorizedView(long bindingEpoch, String workspacePath) {}

  private record SessionKey(EnvironmentId environmentId, String viewId, long bindingEpoch) {}

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
      return new SessionKey(identity.environmentId(), viewId, bindingEpoch);
    }
  }
}
