package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;
import fun.fengwk.kkstudio.platform.environment.ide.IdeViewAuthorizationListener;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IDE 独立 Origin 的浏览器会话：一次性 bootstrap ticket 消费后设置 host-only/HttpOnly cookie，绑定 {@link
 * IdeSessionIdentity} 代际（Environment + Daemon 实例 + 租约代币），而不是绑定单个 workspace 视图。
 *
 * <p>cookie 值是不透明 256-bit 随机串，只在本节点有效；每个请求都要复核其绑定代际是否仍是当前 READY 代际（由数据面完成）。**同一代际只保留一个 cookie**：多次
 * bootstrap（多面板）复用同一 cookie，既不互相覆盖也不会随 bootstrap 次数增长。租约失效/接管时按代际关闭会话与视图授权，使旧 cookie
 * 立即失效；读取时同时判定过期。
 *
 * <p><b>受管 workspace 授权：</b>按代际维护 view → workspace 的授权集合，生命周期跟随真实视图事件（{@link
 * IdeViewAuthorizationListener#viewOpened}/{@link IdeViewAuthorizationListener#viewClosed}）与
 * bootstrap 消费， 不做空闲超时推断。集合有固定上界，代际与会话数量同样有界，超界 fail-closed。
 */
@Component
public final class IdeBrowserSessionRegistry implements IdeViewAuthorizationListener {

  /** 一个浏览器 IDE 会话：绑定代际，并给出该代际当前的受管 workspace 授权视图。 */
  public record Session(IdeSessionIdentity identity, Set<String> authorizedWorkspaces) {

    public Session {
      identity = Objects.requireNonNull(identity, "identity");
      authorizedWorkspaces = Set.copyOf(authorizedWorkspaces);
    }

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }

    /** 该代际是否已授权访问某个受管 workspace（IDE 根路径上的 {@code workspace}/{@code folder} 只能取受管值）。 */
    public boolean authorizesWorkspace(String workspacePath) {
      return workspacePath != null && authorizedWorkspaces.contains(workspacePath);
    }
  }

  private static final int TOKEN_BYTES = 32;

  /** 单代际的视图授权上界；真实额度由 Daemon 的 OPEN_VIEW 约束，这里只是内存安全上界。 */
  static final int MAX_VIEWS_PER_GENERATION = 64;

  /** 并存代际（会话）上界；每次 open 先清理过期代际，仍超界即 fail-closed。 */
  static final int MAX_GENERATIONS = 4096;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;
  private final Map<Generation, GenerationState> generations = new ConcurrentHashMap<>();
  private final Map<String, Generation> cookies = new ConcurrentHashMap<>();

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
   * 建立一个浏览器会话并返回 cookie 值；同一代际复用既有有效 cookie。
   *
   * @throws IllegalStateException 并存代际已达上界（fail-closed，不静默淘汰他人会话）
   */
  public String open(IdeSessionIdentity identity, String viewId, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    Instant now = clock.instant();
    purgeExpired(now);
    Generation generation = new Generation(identity);
    GenerationState state = generations.get(generation);
    if (state == null) {
      if (generations.size() >= MAX_GENERATIONS) {
        throw new IllegalStateException("too many concurrent IDE browser sessions");
      }
      state = new GenerationState();
      generations.put(generation, state);
    }
    authorize(generation, state, viewId, workspacePath);
    synchronized (state) {
      if (state.cookie == null || !state.expiresAt.isAfter(now)) {
        state.cookie = newToken();
        cookies.put(state.cookie, generation);
      }
      state.expiresAt = now.plus(sessionTtl);
      return state.cookie;
    }
  }

  /** 解析 cookie 值；不存在或过期返回 empty；返回的授权视图是该代际当前的集合。 */
  public Optional<Session> lookup(String cookieValue) {
    if (cookieValue == null || cookieValue.isBlank()) {
      return Optional.empty();
    }
    Generation generation = cookies.get(cookieValue);
    if (generation == null) {
      return Optional.empty();
    }
    GenerationState state = generations.get(generation);
    Instant now = clock.instant();
    if (state == null || !state.expiresAt.isAfter(now)) {
      cookies.remove(cookieValue, generation);
      return Optional.empty();
    }
    synchronized (state) {
      return Optional.of(
          new Session(generation.identity, new LinkedHashSet<>(state.views.values())));
    }
  }

  /** 关闭某代际的全部会话与视图授权（租约失效/接管）。 */
  public void closeGeneration(EnvironmentId environmentId, UUID leaseToken) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    for (Map.Entry<Generation, GenerationState> entry : generations.entrySet()) {
      Generation generation = entry.getKey();
      if (!generation.identity.environmentId().equals(environmentId)
          || !generation.identity.leaseToken().equals(leaseToken)) {
        continue;
      }
      if (generations.remove(generation, entry.getValue())) {
        String cookie = entry.getValue().cookie;
        if (cookie != null) {
          cookies.remove(cookie, generation);
        }
      }
    }
  }

  @Override
  public void viewOpened(IdeSessionIdentity identity, String viewId, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(workspacePath, "workspacePath");
    Generation generation = new Generation(identity);
    GenerationState state =
        generations.computeIfAbsent(generation, ignored -> new GenerationState());
    authorize(generation, state, viewId, workspacePath);
  }

  @Override
  public void viewClosed(IdeSessionIdentity identity, String viewId) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(viewId, "viewId");
    GenerationState state = generations.get(new Generation(identity));
    if (state != null) {
      synchronized (state) {
        state.views.remove(viewId);
      }
    }
  }

  /** 当前会话数量（诊断/测试）。 */
  public int size() {
    return cookies.size();
  }

  private void authorize(
      Generation generation, GenerationState state, String viewId, String workspacePath) {
    synchronized (state) {
      if (state.views.size() >= MAX_VIEWS_PER_GENERATION && !state.views.containsKey(viewId)) {
        // 上界保护：超出即不再授权，不静默淘汰其它视图（真实额度由 Daemon OPEN_VIEW 约束）。
        return;
      }
      state.views.put(viewId, workspacePath);
    }
  }

  private void purgeExpired(Instant now) {
    for (Map.Entry<Generation, GenerationState> entry : generations.entrySet()) {
      GenerationState state = entry.getValue();
      if (state.expiresAt.isAfter(now)) {
        continue;
      }
      if (generations.remove(entry.getKey(), state)) {
        String cookie = state.cookie;
        if (cookie != null) {
          cookies.remove(cookie, entry.getKey());
        }
      }
    }
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** 一个代际的会话状态：复用的 cookie 与 view → workspace 授权集合。 */
  private static final class GenerationState {

    private String cookie;
    private Instant expiresAt = Instant.EPOCH;
    private final Map<String, String> views = new ConcurrentHashMap<>();
  }

  private record Generation(IdeSessionIdentity identity) {}
}
