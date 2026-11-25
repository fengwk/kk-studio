package fun.fengwk.kkstudio.ide.gateway;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * IDE 浏览器 cookie 会话登记。
 *
 * <p>cookie 严格绑定 Environment + Daemon 实例 + viewId + bindingEpoch，并只通过 view 作用域 Path 下发。本类只管理一次性
 * bootstrap 之后的 cookie 生命周期；视图是否存在、是否仍属于该 epoch 与 workspace 完全由 {@link
 * fun.fengwk.kkstudio.ide.IdeViewSnapshotStore} 的唯一权威快照裁决。签发新 cookie 时回收已过期条目与同一 view 更旧 epoch 的
 * 条目，条目数不随历史打开次数增长。
 */
@Component
public final class IdeBrowserSessionRegistry {

  /** 一个浏览器 IDE cookie 会话；授权仍需匹配权威快照。 */
  public record Session(
      DaemonConnectionIdentity identity,
      String viewId,
      long bindingEpoch,
      String workspacePath,
      UUID userId) {

    public Session {
      identity = Objects.requireNonNull(identity, "identity");
      viewId = Objects.requireNonNull(viewId, "viewId");
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
      workspacePath = Objects.requireNonNull(workspacePath, "workspacePath");
      userId = Objects.requireNonNull(userId, "userId");
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
      DaemonConnectionIdentity identity,
      String viewId,
      long bindingEpoch,
      String workspacePath,
      UUID userId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    Objects.requireNonNull(userId, "userId");
    if (bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be positive");
    }
    synchronized (lock) {
      SessionKey key = new SessionKey(identity, viewId, bindingEpoch);
      Instant now = clock.instant();
      reclaim(identity, viewId, bindingEpoch, now);
      String existingToken = tokenBySessionKey.get(key);
      SessionState existing = existingToken == null ? null : sessions.get(existingToken);
      if (existing != null
          && existing.workspacePath.equals(workspacePath)
          && existing.userId.equals(userId)) {
        existing.expiresAt = now.plus(sessionTtl);
        return existingToken;
      }
      if (existingToken != null) {
        removeSession(existingToken);
      }
      String token = newToken();
      SessionState session =
          new SessionState(
              identity, viewId, bindingEpoch, workspacePath, userId, now.plus(sessionTtl));
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
          new Session(
              state.identity, state.viewId, state.bindingEpoch, state.workspacePath, state.userId));
    }
  }

  /**
   * 回收已过期条目与同一 view 更旧 epoch 的条目，避免注册表随历史打开次数单调增长。
   *
   * <p>只回收严格更旧的 epoch：迟到的旧 epoch 打开不能撤销新 epoch 已签发的 cookie。
   */
  private void reclaim(
      DaemonConnectionIdentity identity, String viewId, long bindingEpoch, Instant now) {
    Iterator<Map.Entry<String, SessionState>> iterator = sessions.entrySet().iterator();
    while (iterator.hasNext()) {
      SessionState state = iterator.next().getValue();
      boolean expired = !state.expiresAt.isAfter(now);
      boolean staleEpoch =
          state.bindingEpoch < bindingEpoch
              && state.identity.equals(identity)
              && state.viewId.equals(viewId);
      if (expired || staleEpoch) {
        iterator.remove();
        tokenBySessionKey.remove(state.key());
      }
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
    private final UUID userId;
    private Instant expiresAt;

    private SessionState(
        DaemonConnectionIdentity identity,
        String viewId,
        long bindingEpoch,
        String workspacePath,
        UUID userId,
        Instant expiresAt) {
      this.identity = identity;
      this.viewId = viewId;
      this.bindingEpoch = bindingEpoch;
      this.workspacePath = workspacePath;
      this.userId = userId;
      this.expiresAt = expiresAt;
    }

    private SessionKey key() {
      return new SessionKey(identity, viewId, bindingEpoch);
    }
  }
}
