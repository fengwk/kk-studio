package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.IdeViewAuthorizationListener;

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
 * <p>cookie 严格绑定 Environment + Daemon 实例 + viewId + bindingEpoch，并只应通过 view 作用域 Path 下发，因此一个 view 的
 * cookie 不能访问 另一个 view。视图授权跟随 Daemon 的真实 claim/close 事件：claim 成功登记该 epoch，close/detach/恢复换代撤销旧
 * epoch；旧 epoch 的迟到关闭 不会撤销新绑定，实例接管清空整个代际。
 *
 * <p>全部元数据由单一 {@link #lock} 串行访问；超界显式失败，绝不淘汰他人会话。本登记不是用户级 ACL，同 Environment 的视图仍共享同一 Daemon
 * 权限，隔离只发生在 view+epoch 的入口凭据层。
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

    /** IDE 根路径上的 {@code workspace}/{@code folder} 只能指向本 view 的受管 workspace。 */
    public boolean authorizesWorkspace(String candidate) {
      return candidate != null && workspacePath.equals(candidate);
    }
  }

  private static final int TOKEN_BYTES = 32;

  /** 单代际的视图授权上界；与 Daemon 的每 Environment 视图额度一致，超出显式失败。 */
  static final int MAX_VIEWS_PER_GENERATION = 64;

  /** 并存代际（Daemon 实例会话）上界；超界显式失败，不淘汰他人会话。 */
  static final int MAX_GENERATIONS = 4096;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;

  private final Object lock = new Object();

  /** 以下映射与其中每个状态对象都由 {@link #lock} 串行保护。 */
  private final Map<Generation, GenerationState> generations = new HashMap<>();

  private final Map<String, SessionState> sessions = new HashMap<>();
  private final Map<SessionKey, String> tokenBySessionKey = new HashMap<>();

  @Autowired
  public IdeBrowserSessionRegistry(IdeGatewayProperties properties) {
    this(new SecureRandom(), Clock.systemUTC(), properties.requireSessionTtl());
  }

  /** 测试用构造：可注入确定性时钟与 TTL。 */
  IdeBrowserSessionRegistry(SecureRandom random, Clock clock, Duration sessionTtl) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
  }

  /**
   * 消费一次性 bootstrap ticket：只校验「该 view+epoch 已由真实 claim 授权且 workspace 匹配」，随后设置/复用该 view 的 cookie。
   *
   * <p>绝不新增授权：旧 ticket 不能在视图关闭或恢复换代后重新授权。
   *
   * @return 该 view+epoch 的 cookie 值
   * @throws IllegalStateException 该代际/视图/epoch 未被真实 claim 授权，或 workspace 不匹配
   */
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

  /** 解析某 view 路径上的 cookie；不存在、过期、代际/epoch 换代或视图关闭都返回 empty。 */
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

  /** 关闭某代际的会话与全部视图授权（另一个 Daemon 实例接管）。 */
  public void closeGeneration(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    synchronized (lock) {
      generations.remove(new Generation(identity));
      sessions
          .entrySet()
          .removeIf(
              entry -> {
                boolean matches = entry.getValue().identity.equals(identity);
                if (matches) {
                  tokenBySessionKey.remove(entry.getValue().key());
                }
                return matches;
              });
    }
  }

  /** 在任何 Native 副作用（Daemon 的 {@code OPEN_VIEW}）之前预检容量：该 Environment 现有代际已达视图上界时显式失败。 */
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

  /** 真实视图已 claim/恢复：授权其 epoch 的受管 workspace，并立即使该 view 的旧 epoch 会话失效。 */
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

  /** 真实视图的某 epoch 已关闭/分离：只撤销该 epoch；旧关闭不能影响新绑定。 */
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

  /** 当前有效 cookie 数量（诊断/测试）。 */
  public int size() {
    synchronized (lock) {
      return sessions.size();
    }
  }

  /** 某代际当前的视图授权数量（诊断/测试）。 */
  int viewCount(DaemonConnectionIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    synchronized (lock) {
      GenerationState state = generations.get(new Generation(identity));
      return state == null ? 0 : state.views.size();
    }
  }

  /** 某 view 当前授权数量（诊断/测试）。 */
  int sessionCount(EnvironmentId environmentId, String viewId) {
    synchronized (lock) {
      return (int)
          sessions.values().stream()
              .filter(
                  session ->
                      session.identity.environmentId().equals(environmentId)
                          && session.viewId.equals(viewId))
              .count();
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

  /** 一个 Daemon 实例代际：viewId → 当前 epoch/workspace。 */
  private static final class GenerationState {

    private final Map<String, AuthorizedView> views = new LinkedHashMap<>();
  }

  private record Generation(DaemonConnectionIdentity identity) {}

  private record AuthorizedView(long bindingEpoch, String workspacePath) {}

  private record SessionKey(EnvironmentId environmentId, String viewId, long bindingEpoch) {}

  /** 一条已签发的 view cookie；过期只作废该 cookie，不销毁 Daemon 视图。 */
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
