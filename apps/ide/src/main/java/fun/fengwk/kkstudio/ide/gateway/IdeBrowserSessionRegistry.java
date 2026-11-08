package fun.fengwk.kkstudio.ide.gateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.IdeCurrentGeneration;
import fun.fengwk.kkstudio.ide.IdeViewAuthorizationListener;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * IDE 浏览器会话与受管 workspace 授权登记。
 *
 * <p>cookie 严格绑定 Environment + Daemon 实例 + viewId + bindingEpoch，并只应通过 view 作用域 Path 下发。授权与 token
 * 都以 Daemon 进程身份为代际：同一进程断线重连保留既有会话，另一个进程接管后旧代际的视图登记与 token 被清除，既不与新实例复用，也不占新实例的容量。
 *
 * <p>登记（容量预检与视图授权）在登记锁内先向 {@link IdeCurrentGeneration} 复核身份再清理旧代际：迟到的旧实例结果即使已通过调用方的锁外检查，也不会删除
 * 新代际的登记与 token；关闭只作用于同一进程自身的视图，不受该复核影响。
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
  private final IdeCurrentGeneration currentGenerations;
  private final Object lock = new Object();

  private final Map<Generation, GenerationState> generations = new HashMap<>();
  private final Map<String, SessionState> sessions = new HashMap<>();
  private final Map<SessionKey, String> tokenBySessionKey = new HashMap<>();

  @Autowired
  public IdeBrowserSessionRegistry(IdeCurrentGeneration currentGenerations) {
    this(
        new SecureRandom(),
        Clock.systemUTC(),
        IdeGatewayConstants.DEFAULT_SESSION_TTL,
        currentGenerations);
  }

  public IdeBrowserSessionRegistry(
      SecureRandom random,
      Clock clock,
      Duration sessionTtl,
      IdeCurrentGeneration currentGenerations) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
    this.currentGenerations = Objects.requireNonNull(currentGenerations, "currentGenerations");
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
  public void ensureViewCapacity(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    synchronized (lock) {
      requireCurrentGeneration(identity);
      // 已确认的当前实例接管该 Environment：旧代际的登记与 token 不再保留，容量按新代际自身计算。
      discardReplacedGenerations(identity);
      GenerationState state = generations.get(new Generation(identity));
      if (state != null && state.views.size() >= MAX_VIEWS_PER_GENERATION) {
        throw new IllegalStateException("too many open IDE views for this environment");
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
      requireCurrentGeneration(identity);
      discardReplacedGenerations(identity);
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
        removeAllSessionsForView(identity, viewId);
      } else {
        removeSessionsForView(identity, viewId, bindingEpoch);
      }
    }
  }

  @Override
  public void viewClosed(DaemonConnectionIdentity identity, String viewId, long bindingEpoch) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    synchronized (lock) {
      Generation generation = new Generation(identity);
      GenerationState state = generations.get(generation);
      if (state == null) {
        return;
      }
      AuthorizedView authorized = state.views.get(viewId);
      if (authorized == null || authorized.bindingEpoch != bindingEpoch) {
        return;
      }
      state.views.remove(viewId);
      removeAllSessionsForView(identity, viewId);
      if (state.views.isEmpty()) {
        generations.remove(generation);
      }
    }
  }

  /** 只清掉该 Daemon 进程已过期 epoch 的会话；另一个进程复用相同 view/epoch 的会话不在此撤销范围内。 */
  private void removeSessionsForView(
      DaemonConnectionIdentity identity, String viewId, long keepBindingEpoch) {
    sessions
        .entrySet()
        .removeIf(
            entry -> {
              SessionState session = entry.getValue();
              boolean remove =
                  session.identity.equals(identity)
                      && session.viewId.equals(viewId)
                      && session.bindingEpoch != keepBindingEpoch;
              if (remove) {
                tokenBySessionKey.remove(session.key());
              }
              return remove;
            });
  }

  private void removeAllSessionsForView(DaemonConnectionIdentity identity, String viewId) {
    sessions
        .entrySet()
        .removeIf(
            entry -> {
              SessionState session = entry.getValue();
              boolean remove = session.identity.equals(identity) && session.viewId.equals(viewId);
              if (remove) {
                tokenBySessionKey.remove(session.key());
              }
              return remove;
            });
  }

  /** 登记锁内的身份复核：已不是本节点当前 READY 实例的登记不得清理或覆盖当前代际。 */
  private void requireCurrentGeneration(DaemonConnectionIdentity identity) {
    if (!currentGenerations.current(identity.environmentId()).map(identity::equals).orElse(false)) {
      throw new IllegalStateException(
          "IDE view authorization belongs to a replaced daemon instance");
    }
  }

  /** 确认的当前实例授权前清掉同 Environment 的其它代际：旧进程的视图与 token 不占新代际容量，也不能被复用。 */
  private void discardReplacedGenerations(DaemonConnectionIdentity current) {
    Set<DaemonConnectionIdentity> replaced = new HashSet<>();
    generations
        .keySet()
        .removeIf(
            generation -> {
              DaemonConnectionIdentity identity = generation.identity();
              if (identity.equals(current)
                  || !identity.environmentId().equals(current.environmentId())) {
                return false;
              }
              replaced.add(identity);
              return true;
            });
    sessions.values().removeIf(session -> replaced.contains(session.identity));
    tokenBySessionKey.keySet().removeIf(key -> replaced.contains(key.identity()));
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
