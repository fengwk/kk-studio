package fun.fengwk.kkstudio.harness.infra.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** Owner oversize buffer：UTF-8 字节边界、TTL、条数/字节驱逐与 close 释放。 */
class RealtimePayloadBufferTest {

  private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
  private static final UUID THREAD = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Test
  void storesAndReadsCanonicalUnicodePayloadWithUtf8Counting() {
    MutableClock clock = new MutableClock(NOW);
    RealtimePayloadBuffer buffer = buffer(8, 64, 1024, Duration.ofSeconds(30), clock);
    String canonical = "{\"text\":\"界面\"}";

    UUID payloadId = buffer.put(THREAD, canonical).orElseThrow();

    assertEquals(17, buffer.totalBytes(), "UTF-8 bytes, not UTF-16 chars, must be counted");
    RealtimePayloadBuffer.Payload payload = buffer.get(payloadId).orElseThrow();
    assertEquals(THREAD, payload.threadId());
    assertEquals(canonical, payload.canonicalJson());
  }

  @Test
  void rejectsPayloadAboveSingleEventLimitAtExactUtf8Boundary() {
    MutableClock clock = new MutableClock(NOW);
    RealtimePayloadBuffer buffer = buffer(8, 2, 1024, Duration.ofSeconds(30), clock);

    Optional<UUID> atLimit = buffer.put(THREAD, "aa");
    Optional<UUID> aboveLimit = buffer.put(THREAD, "界a");

    assertTrue(atLimit.isPresent());
    assertTrue(aboveLimit.isEmpty());
    assertEquals(1, buffer.size());
  }

  @Test
  void expiredPayloadIsRemovedAndNeverReturned() {
    MutableClock clock = new MutableClock(NOW);
    RealtimePayloadBuffer buffer = buffer(8, 64, 1024, Duration.ofSeconds(30), clock);
    UUID payloadId = buffer.put(THREAD, "payload").orElseThrow();

    clock.advance(Duration.ofSeconds(29));
    assertTrue(buffer.get(payloadId).isPresent());

    clock.advance(Duration.ofSeconds(1));
    assertTrue(buffer.get(payloadId).isEmpty());
    assertEquals(0, buffer.size());
    assertEquals(0, buffer.totalBytes());
  }

  @Test
  void countBoundEvictsOldestPayload() {
    MutableClock clock = new MutableClock(NOW);
    RealtimePayloadBuffer buffer = buffer(2, 64, 1024, Duration.ofSeconds(30), clock);

    UUID first = buffer.put(THREAD, "one").orElseThrow();
    UUID second = buffer.put(THREAD, "two").orElseThrow();
    UUID third = buffer.put(THREAD, "three").orElseThrow();

    assertTrue(buffer.get(first).isEmpty());
    assertEquals("two", buffer.get(second).orElseThrow().canonicalJson());
    assertEquals("three", buffer.get(third).orElseThrow().canonicalJson());
    assertEquals(2, buffer.size());
  }

  @Test
  void totalByteBoundEvictsOldestPayload() {
    MutableClock clock = new MutableClock(NOW);
    RealtimePayloadBuffer buffer = buffer(8, 64, 10, Duration.ofSeconds(30), clock);

    UUID first = buffer.put(THREAD, "aaaaaa").orElseThrow();
    UUID second = buffer.put(THREAD, "bbbbbb").orElseThrow();

    assertTrue(buffer.get(first).isEmpty());
    assertEquals("bbbbbb", buffer.get(second).orElseThrow().canonicalJson());
    assertEquals(6, buffer.totalBytes());
  }

  @Test
  void closeClearsPayloadsAndMakesFurtherWritesUnavailable() {
    RealtimePayloadBuffer buffer =
        buffer(8, 64, 1024, Duration.ofSeconds(30), new MutableClock(NOW));
    UUID payloadId = buffer.put(THREAD, "payload").orElseThrow();

    buffer.close();
    buffer.close();

    assertTrue(buffer.get(payloadId).isEmpty());
    assertTrue(buffer.put(THREAD, "after-close").isEmpty());
  }

  private static RealtimePayloadBuffer buffer(
      int maxPayloads, int maxEventBytes, long maxTotalBytes, Duration ttl, Clock clock) {
    return new RealtimePayloadBuffer(maxPayloads, maxEventBytes, maxTotalBytes, ttl, clock);
  }

  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
