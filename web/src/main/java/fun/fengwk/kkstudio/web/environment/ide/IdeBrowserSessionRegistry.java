package fun.fengwk.kkstudio.web.environment.ide;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.IdeSessionIdentity;

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
 * <p>cookie 值是不透明 256-bit 随机串，只在本节点有效；每个请求都要复核其绑定代际是否仍是当前 READY 代际（由数据面完成）。租约失效/接管时按代际
 * 关闭全部会话与已授权视图，使旧 cookie 立即失效；读取时同时判定过期。
 *
 * <p><b>多窗口/多面板：</b>受管 workspace 授权按**代际**维护并带 TTL——每个成功消费的 bootstrap ticket 把其 workspace
 * 记入该代际的授权集合，因此同一 Daemon 代际下新开的面板（另一个 workspace 视图）不会因为 cookie 只绑定了首个 workspace 而被
 * 403；视图长期未再出现即过期移除。
 */
@Component
public final class IdeBrowserSessionRegistry {

  /** 一个浏览器 IDE 会话：绑定代际，并给出该代际当前（未过期）的受管 workspace 授权视图。 */
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

  private final SecureRandom random;
  private final Clock clock;
  private final Duration sessionTtl;
  private final Duration viewTtl;
  private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
  private final Map<Generation, Map<String, Instant>> generationViews = new ConcurrentHashMap<>();

  @Autowired
  public IdeBrowserSessionRegistry(IdeGatewayProperties properties) {
    this(
        new SecureRandom(),
        Clock.systemUTC(),
        properties.requireSessionTtl(),
        properties.requireViewTtl());
  }

  /** 测试用构造：可注入确定性时钟与 TTL。 */
  IdeBrowserSessionRegistry(SecureRandom random, Clock clock, Duration ttl) {
    this(random, clock, ttl, ttl);
  }

  IdeBrowserSessionRegistry(
      SecureRandom random, Clock clock, Duration sessionTtl, Duration viewTtl) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
    this.viewTtl = Objects.requireNonNull(viewTtl, "viewTtl");
  }

  /** 建立一个浏览器会话、把该视图授权给整个代际，并返回 cookie 值。 */
  public String open(IdeSessionIdentity identity, String workspacePath) {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(workspacePath, "workspacePath");
    Instant now = clock.instant();
    authorize(identity, workspacePath, now);
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    sessions.put(value, new Entry(new Generation(identity), now.plus(sessionTtl)));
    return value;
  }

  /** 解析 cookie 值；不存在或过期返回 empty；返回的授权视图是该代际当前未过期的集合。 */
  public Optional<Session> lookup(String cookieValue) {
    if (cookieValue == null || cookieValue.isBlank()) {
      return Optional.empty();
    }
    Entry entry = sessions.get(cookieValue);
    if (entry == null) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (!entry.expiresAt.isAfter(now)) {
      sessions.remove(cookieValue, entry);
      return Optional.empty();
    }
    return Optional.of(
        new Session(entry.generation.identity, authorizedWorkspaces(entry.generation, now)));
  }

  /** 关闭某代际的全部会话与视图授权（租约失效/接管）。 */
  public void closeGeneration(EnvironmentId environmentId, UUID leaseToken) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    sessions
        .values()
        .removeIf(
            entry ->
                entry.generation.identity.environmentId().equals(environmentId)
                    && entry.generation.identity.leaseToken().equals(leaseToken));
    generationViews
        .keySet()
        .removeIf(
            generation ->
                generation.identity.environmentId().equals(environmentId)
                    && generation.identity.leaseToken().equals(leaseToken));
  }

  /** 当前会话数量（诊断/测试）。 */
  public int size() {
    return sessions.size();
  }

  private void authorize(IdeSessionIdentity identity, String workspacePath, Instant now) {
    generationViews
        .computeIfAbsent(new Generation(identity), ignored -> new ConcurrentHashMap<>())
        .put(workspacePath, now.plus(viewTtl));
  }

  /** 代际当前未过期的受管 workspace 集合；读取时顺手淘汰过期视图（TTL 生命周期）。 */
  private Set<String> authorizedWorkspaces(Generation generation, Instant now) {
    Map<String, Instant> views = generationViews.get(generation);
    if (views == null) {
      return Set.of();
    }
    views.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    if (views.isEmpty()) {
      generationViews.remove(generation, views);
      return Set.of();
    }
    return new LinkedHashSet<>(views.keySet());
  }

  private record Entry(Generation generation, Instant expiresAt) {}

  private record Generation(IdeSessionIdentity identity) {}
}
