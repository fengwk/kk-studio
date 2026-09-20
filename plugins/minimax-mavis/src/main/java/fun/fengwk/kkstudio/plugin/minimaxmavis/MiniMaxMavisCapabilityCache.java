package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * capability catalog 的短 TTL 缓存。
 *
 * <p>15 个 Tool 是启动期冻结的静态定义，Provider 侧 endpoint 目录却可能变化：调用时先用当前 token 对齐一次线上目录，把稳定 Tool 映射到当前
 * endpoint；命中未过期的缓存即复用，避免每次调用都多发一次 catalog 请求。能力缺失由调用方给出确定性的 {@code
 * MAVIS_CAPABILITY_UNAVAILABLE}，绝不动态增删 Tool 或改变 schema。
 *
 * <p>缓存按 region 分桶，token 拒绝或网络失败都不写缓存，直接向上抛出确定性错误。
 */
public final class MiniMaxMavisCapabilityCache {

  /** 目录缓存 TTL：短到能跟上 Provider 变化，长到不放大调用次数。 */
  public static final Duration TTL = Duration.ofMinutes(5);

  private final MavisClient client;
  private final Clock clock;
  private final Map<MavisRegion, Entry> entries = new EnumMap<>(MavisRegion.class);

  public MiniMaxMavisCapabilityCache(MavisClient client) {
    this(client, Clock.systemDefaultZone());
  }

  MiniMaxMavisCapabilityCache(MavisClient client, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 返回当前 provider 的 capability catalog，必要时在线刷新一次。 */
  public synchronized MavisCapabilityCatalog catalog(MavisCredential credential) {
    Objects.requireNonNull(credential, "credential");
    Instant now = clock.instant();
    Entry cached = entries.get(credential.region());
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.catalog();
    }
    MavisCapabilityCatalog fetched = client.fetchCatalog(credential.token(), credential.region());
    entries.put(credential.region(), new Entry(fetched, now.plus(TTL)));
    return fetched;
  }

  private record Entry(MavisCapabilityCatalog catalog, Instant expiresAt) {}
}
