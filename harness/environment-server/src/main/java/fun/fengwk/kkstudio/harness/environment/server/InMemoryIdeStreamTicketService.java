package fun.fengwk.kkstudio.harness.environment.server;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.IdeStreamMode;

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
 * 进程内一次性 IDE 流 ticket 实现：{@code ConcurrentHashMap} 存储 + 消费即删除。
 *
 * <p>只在本节点（当前 owner）内有效，不做跨节点复制：跨节点请求先由 owner 定位再本地消费。ticket 为 256-bit {@link SecureRandom}
 * 值、base64url（无 padding）编码；过期由读取时判定，无需后台任务。
 */
public final class InMemoryIdeStreamTicketService implements IdeStreamTicketService {

  /** ticket 默认短时有效期：足够 Daemon 建立反向连接，过期即作废。 */
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

  private static final int TICKET_BYTES = 32;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration ttl;
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

  public InMemoryIdeStreamTicketService() {
    this(new SecureRandom(), Clock.systemUTC(), DEFAULT_TTL);
  }

  public InMemoryIdeStreamTicketService(SecureRandom random, Clock clock, Duration ttl) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
  }

  @Override
  public Issued issue(EnvironmentId environmentId, UUID leaseToken, IdeStreamMode mode) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(mode, "mode");
    byte[] bytes = new byte[TICKET_BYTES];
    random.nextBytes(bytes);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tickets.put(ticket, new Ticket(environmentId, leaseToken, mode, clock.instant().plus(ttl)));
    return new Issued(ticket, mode);
  }

  @Override
  public Optional<Consumed> consume(String ticket) {
    if (ticket == null || ticket.isBlank()) {
      return Optional.empty();
    }
    Ticket entry = tickets.remove(ticket);
    if (entry == null || !entry.expiresAt.isAfter(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(new Consumed(entry.environmentId, entry.leaseToken, entry.mode));
  }

  @Override
  public void revoke(EnvironmentId environmentId, UUID leaseToken) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(leaseToken, "leaseToken");
    tickets
        .values()
        .removeIf(
            entry ->
                entry.environmentId.equals(environmentId) && entry.leaseToken.equals(leaseToken));
  }

  /** 当前未消费 ticket 数量（用于测试与诊断，不暴露 ticket 值）。 */
  public int pendingCount() {
    return tickets.size();
  }

  private static final class Ticket {

    private final EnvironmentId environmentId;
    private final UUID leaseToken;
    private final IdeStreamMode mode;
    private final Instant expiresAt;

    private Ticket(
        EnvironmentId environmentId, UUID leaseToken, IdeStreamMode mode, Instant expiresAt) {
      this.environmentId = environmentId;
      this.leaseToken = leaseToken;
      this.mode = mode;
      this.expiresAt = expiresAt;
    }
  }
}
