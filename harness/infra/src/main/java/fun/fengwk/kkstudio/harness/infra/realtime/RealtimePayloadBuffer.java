package fun.fengwk.kkstudio.harness.infra.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimePayloadLimits;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner 节点短期、有界、纯内存的 oversize realtime payload buffer。
 *
 * <p>只保存 PostgreSQL NOTIFY 承载不下的 canonical {@code RealtimeEvent}
 * JSON。不写文件、不新增数据库表；TTL、总字节、单事件字节与条数同时约束 内存。容量不足时按写入顺序驱逐最旧 payload；被驱逐或过期后读取明确返回空，调用方必须
 * RESYNC，绝不截断或伪造内容。
 *
 * <p>实现是单一锁保护的 {@link LinkedHashMap}：事件频率低且只操作小 map，不需要更复杂的分段结构。日志只记录数量/字节/id，不记录 payload。
 */
public final class RealtimePayloadBuffer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RealtimePayloadBuffer.class);

  private final int maxPayloads;
  private final int maxEventBytes;
  private final long maxTotalBytes;
  private final long ttlMillis;
  private final Clock clock;

  /** 插入顺序即驱逐顺序；值包含固定 TTL 到期时刻。 */
  private final Map<UUID, Entry> entries = new LinkedHashMap<>();

  private long totalBytes;
  private boolean closed;

  public RealtimePayloadBuffer() {
    this(
        RealtimePayloadLimits.MAX_PAYLOADS,
        RealtimePayloadLimits.MAX_EVENT_BYTES,
        RealtimePayloadLimits.MAX_TOTAL_BYTES,
        RealtimePayloadLimits.TTL,
        Clock.systemUTC());
  }

  RealtimePayloadBuffer(
      int maxPayloads, int maxEventBytes, long maxTotalBytes, Duration ttl, Clock clock) {
    if (maxPayloads <= 0) {
      throw new IllegalArgumentException("maxPayloads must be positive");
    }
    if (maxEventBytes <= 0) {
      throw new IllegalArgumentException("maxEventBytes must be positive");
    }
    if (maxTotalBytes <= 0) {
      throw new IllegalArgumentException("maxTotalBytes must be positive");
    }
    Objects.requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    this.maxPayloads = maxPayloads;
    this.maxEventBytes = maxEventBytes;
    this.maxTotalBytes = maxTotalBytes;
    this.ttlMillis = ttl.toMillis();
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 保存一条 oversize canonical event。
   *
   * @return 成功时返回可供 REFERENCE 使用的 payload id；单事件超限或 buffer 已关闭时返回空
   */
  public synchronized Optional<UUID> put(UUID threadId, String canonicalJson) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(canonicalJson, "canonicalJson");
    if (closed) {
      return Optional.empty();
    }
    byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxEventBytes) {
      log.debug(
          "realtime payload rejected threadId={} bytes={} maxEventBytes={}",
          threadId,
          bytes.length,
          maxEventBytes);
      return Optional.empty();
    }
    if (bytes.length > maxTotalBytes) {
      return Optional.empty();
    }
    long now = clock.millis();
    pruneExpired(now);
    evictUntilWithinBounds(bytes.length);
    UUID payloadId = UUID.randomUUID();
    entries.put(payloadId, new Entry(threadId, canonicalJson, bytes.length, now + ttlMillis));
    totalBytes += bytes.length;
    log.debug(
        "realtime payload stored payloadId={} threadId={} bytes={} entries={} totalBytes={}",
        payloadId,
        threadId,
        bytes.length,
        entries.size(),
        totalBytes);
    return Optional.of(payloadId);
  }

  /** 读取未过期 payload；过期条目在读取时移除并返回空。 */
  public synchronized Optional<Payload> get(UUID payloadId) {
    Objects.requireNonNull(payloadId, "payloadId");
    if (closed) {
      return Optional.empty();
    }
    pruneExpired(clock.millis());
    Entry entry = entries.get(payloadId);
    if (entry == null) {
      return Optional.empty();
    }
    return Optional.of(new Payload(payloadId, entry.threadId, entry.canonicalJson));
  }

  synchronized int size() {
    pruneExpired(clock.millis());
    return entries.size();
  }

  synchronized long totalBytes() {
    pruneExpired(clock.millis());
    return totalBytes;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    entries.clear();
    totalBytes = 0;
  }

  private void evictUntilWithinBounds(long incomingBytes) {
    while (!entries.isEmpty()
        && (entries.size() >= maxPayloads || totalBytes + incomingBytes > maxTotalBytes)) {
      Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
      Map.Entry<UUID, Entry> eldest = iterator.next();
      iterator.remove();
      totalBytes -= eldest.getValue().byteSize;
      log.debug(
          "realtime payload evicted payloadId={} threadId={} bytes={}",
          eldest.getKey(),
          eldest.getValue().threadId,
          eldest.getValue().byteSize);
    }
  }

  private void pruneExpired(long now) {
    Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Entry> entry = iterator.next();
      if (entry.getValue().expiresAtMillis <= now) {
        iterator.remove();
        totalBytes -= entry.getValue().byteSize;
        log.debug(
            "realtime payload expired payloadId={} threadId={} bytes={}",
            entry.getKey(),
            entry.getValue().threadId,
            entry.getValue().byteSize);
      }
    }
  }

  /** 一条可回拉的 canonical event；内容不进入日志。 */
  public record Payload(UUID payloadId, UUID threadId, String canonicalJson) {

    public Payload {
      Objects.requireNonNull(payloadId, "payloadId");
      Objects.requireNonNull(threadId, "threadId");
      Objects.requireNonNull(canonicalJson, "canonicalJson");
    }
  }

  private record Entry(UUID threadId, String canonicalJson, long byteSize, long expiresAtMillis) {}
}
