package fun.fengwk.kkstudio.harness.environment.server;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 通用一次性令牌存储：256-bit {@link SecureRandom} 值、base64url（无 padding）编码，消费即删除、读取时判定过期。
 *
 * <p>反向数据面的 stream ticket 与 IDE 同源网关的 bootstrap ticket 语义一致（一次性、短时、绑定当前实例/租约），
 * 因此共享本实现。它只在本节点内有效：跨节点请求先由 owner 定位再本地消费，不做复制。无后台任务，过期由读取时判定。
 *
 * @param <T> 令牌绑定的值（如 Environment+租约+模式，或 Environment+workspace 路径）
 */
public final class OneTimeTicketStore<T> {

  /** 默认短时有效期：足够一次入口/建连跳转，过期即作废。 */
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

  private static final int TICKET_BYTES = 32;

  private final SecureRandom random;
  private final Clock clock;
  private final Duration ttl;
  private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();

  public OneTimeTicketStore() {
    this(new SecureRandom(), Clock.systemUTC(), DEFAULT_TTL);
  }

  public OneTimeTicketStore(SecureRandom random, Clock clock, Duration ttl) {
    this.random = Objects.requireNonNull(random, "random");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
  }

  /** 铸造一条新令牌并绑定 {@code value}。 */
  public String issue(T value) {
    Objects.requireNonNull(value, "value");
    byte[] bytes = new byte[TICKET_BYTES];
    random.nextBytes(bytes);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    entries.put(ticket, new Entry<>(value, clock.instant().plus(ttl)));
    return ticket;
  }

  /** 一次性消费：不存在/已消费/已过期返回 empty。 */
  public Optional<T> consume(String ticket) {
    if (ticket == null || ticket.isBlank()) {
      return Optional.empty();
    }
    Entry<T> entry = entries.remove(ticket);
    if (entry == null || !entry.expiresAt.isAfter(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(entry.value);
  }

  /** 撤销所有匹配的未消费令牌（租约失效/接管时按绑定值清理）。 */
  public void revoke(Predicate<? super T> predicate) {
    Objects.requireNonNull(predicate, "predicate");
    entries.values().removeIf(entry -> predicate.test(entry.value));
  }

  /** 当前未消费令牌数量（诊断/测试；不暴露令牌值）。 */
  public int pendingCount() {
    return entries.size();
  }

  private static final class Entry<T> {

    private final T value;
    private final Instant expiresAt;

    private Entry(T value, Instant expiresAt) {
      this.value = value;
      this.expiresAt = expiresAt;
    }
  }
}
