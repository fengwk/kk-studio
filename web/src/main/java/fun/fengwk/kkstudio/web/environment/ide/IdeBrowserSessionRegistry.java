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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IDE 独立 Origin 的浏览器会话：一次性 bootstrap ticket 消费后设置 host-only/HttpOnly cookie，绑定 {@link
 * IdeSessionIdentity} 与受管 workspace 路径。
 *
 * <p>cookie 值是不透明 256-bit 随机串，只在本节点有效；每个请求都要复核其绑定代际是否仍是当前 READY 代际（由数据面完成）。租约失效/接管时按代际 关闭全部会话，使旧
 * cookie 立即失效；读取时同时判定过期。
 */
@Component
public final class IdeBrowserSessionRegistry {

  /** 一个浏览器 IDE 会话：代际 + 受管 workspace 路径（来自 Daemon 的 OPEN_VIEW 元数据，不接受浏览器任意 path）。 */
  public record Session(IdeSessionIdentity identity, String workspacePath) {

    public Session {
      identity = Objects.requireNonNull(identity, "identity");
      workspacePath = Objects.requireNonNull(workspacePath, "workspacePath");
    }

    public EnvironmentId environmentId() {
      return identity.environmentId();
    }
  }

  private static final int TOKEN_BYTES = 32;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration ttl;
  private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

  @Autowired
  public IdeBrowserSessionRegistry(IdeGatewayProperties properties) {
    this(new SecureRandom(), Clock.systemUTC(), properties.requireSessionTtl());
  }

  /** 测试用构造：可注入确定性时钟与 TTL。 */
  IdeBrowserSessionRegistry(SecureRandom random, Clock clock, Duration ttl) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  /** 建立一个浏览器会话并返回 cookie 值。 */
  public String open(IdeSessionIdentity identity, String workspacePath) {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    sessions.put(value, new Entry(new Session(identity, workspacePath), clock.instant().plus(ttl)));
    return value;
  }

  /** 解析 cookie 值；不存在或过期返回 empty。 */
  public Optional<Session> lookup(String cookieValue) {
    if (cookieValue == null || cookieValue.isBlank()) {
      return Optional.empty();
    }
    Entry entry = sessions.get(cookieValue);
    if (entry == null || !entry.expiresAt.isAfter(clock.instant())) {
      sessions.remove(cookieValue, entry);
      return Optional.empty();
    }
    return Optional.of(entry.session);
  }

  /** 关闭某代际的全部会话（租约失效/接管）。 */
  public void closeGeneration(EnvironmentId environmentId, UUID leaseToken) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    sessions
        .values()
        .removeIf(
            entry ->
                entry.session.identity().environmentId().equals(environmentId)
                    && entry.session.identity().leaseToken().equals(leaseToken));
  }

  /** 当前会话数量（诊断/测试）。 */
  public int size() {
    return sessions.size();
  }

  private record Entry(Session session, Instant expiresAt) {}
}
