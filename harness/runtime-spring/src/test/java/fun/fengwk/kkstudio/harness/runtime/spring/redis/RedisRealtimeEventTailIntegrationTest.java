package fun.fengwk.kkstudio.harness.runtime.spring.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.core.StringRedisTemplate;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEvent;
import fun.fengwk.kkstudio.harness.runtime.realtime.RealtimeEventJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.spring.redis.RealtimeEventTail.Record;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** RedisRealtimeEventTail 集成测试：exclusive read-after/paging/live edge/blocking/缺失字段。 */
class RedisRealtimeEventTailIntegrationTest {

  private static final RedisRealtimeConfig CONFIG = new RedisRealtimeConfig();
  private static final RealtimeEventJsonCodec CODEC = new RealtimeEventJsonCodec();
  private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

  private StringRedisTemplate template;
  private RedisRealtimeEventTail tail;
  private RedisRealtimeEventSink sink;

  @BeforeEach
  void setUp() {
    template = RedisRealtimeFixture.template();
    RedisRealtimeFixture.reset(CONFIG.prefix());
    tail = new RedisRealtimeEventTail(template, CONFIG, CODEC);
    sink = new RedisRealtimeEventSink(template, CONFIG, CODEC);
  }

  @Test
  void initialCursorIsAStableConcreteLiveEdge() {
    assertEquals("0-0", tail.initialCursor(21L));
    appendModelDelta(21L, "one");
    appendModelDelta(21L, "two");
    List<Record> retained = tail.readAfter(21L, "0-0", 10, Duration.ZERO);
    assertEquals(retained.get(1).id(), tail.initialCursor(21L));
    assertThrows(IllegalArgumentException.class, () -> tail.initialCursor(0L));
  }

  @Test
  void readsOnlyRecordsAfterExclusiveCursor() {
    appendModelDelta(11L, "one");
    appendModelDelta(11L, "two");

    List<Record> firstPage = tail.readAfter(11L, "0-0", 10, Duration.ZERO);
    assertEquals(2, firstPage.size());
    assertEquals(CODEC.encode(modelDelta(11L, "one")), firstPage.get(0).payloadJson());

    List<Record> secondPage = tail.readAfter(11L, firstPage.get(0).id(), 10, Duration.ZERO);
    assertEquals(1, secondPage.size());
    assertEquals(firstPage.get(1).id(), secondPage.get(0).id());
    assertEquals(CODEC.encode(modelDelta(11L, "two")), secondPage.get(0).payloadJson());
  }

  @Test
  void countLimitsPageSizeAndPagingContinuesFromCursor() {
    appendModelDelta(12L, "frag-0");
    appendModelDelta(12L, "frag-1");
    appendModelDelta(12L, "frag-2");

    List<Record> firstPage = tail.readAfter(12L, "0-0", 2, Duration.ZERO);
    assertEquals(2, firstPage.size());
    assertEquals(CODEC.encode(modelDelta(12L, "frag-0")), firstPage.get(0).payloadJson());

    List<Record> rest = tail.readAfter(12L, firstPage.get(1).id(), 10, Duration.ZERO);
    assertEquals(1, rest.size());
    assertEquals(CODEC.encode(modelDelta(12L, "frag-2")), rest.get(0).payloadJson());
  }

  @Test
  void legacyCursorsStartFromRetainedWindow() {
    appendModelDelta(13L, "one");
    appendModelDelta(13L, "two");

    assertEquals(2, tail.readAfter(13L, null, 10, Duration.ZERO).size());
    assertEquals(2, tail.readAfter(13L, "", 10, Duration.ZERO).size());
    assertEquals(2, tail.readAfter(13L, "0", 10, Duration.ZERO).size());
  }

  @Test
  void liveEdgeDoesNotReplayRetainedRecords() {
    appendModelDelta(14L, "one");
    appendModelDelta(14L, "two");
    String liveEdge = tail.initialCursor(14L);
    assertTrue(tail.readAfter(14L, liveEdge, 10, Duration.ZERO).isEmpty());
  }

  @Test
  void stableCursorDoesNotLoseWritesBetweenEmptyReads() {
    long threadId = 101L;
    String cursor = tail.initialCursor(threadId);
    assertEquals("0-0", cursor);
    assertTrue(tail.readAfter(threadId, cursor, 10, Duration.ZERO).isEmpty());

    sink.append(modelDelta(threadId, "not-lost"));

    List<Record> records = tail.readAfter(threadId, cursor, 10, Duration.ZERO);
    assertEquals(1, records.size());
    assertEquals(CODEC.encode(modelDelta(threadId, "not-lost")), records.get(0).payloadJson());
  }

  @Test
  void blockingReadReturnsRecordsAppendedWhileWaiting() throws Exception {
    long threadId = 15L;
    String cursor = tail.initialCursor(threadId);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<List<Record>> result =
          executor.submit(() -> tail.readAfter(threadId, cursor, 10, Duration.ofSeconds(4)));
      Thread.sleep(100);
      sink.append(modelDelta(threadId, "live"));

      List<Record> records = result.get(10, TimeUnit.SECONDS);
      assertNotNull(records);
      assertEquals(1, records.size());
      assertEquals(CODEC.encode(modelDelta(threadId, "live")), records.get(0).payloadJson());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  @Timeout(5)
  void blockingReadTimesOutEmptyWhenNothingArrives() {
    long start = System.nanoTime();
    List<Record> records = tail.readAfter(16L, tail.initialCursor(16L), 10, Duration.ofMillis(300));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertTrue(records.isEmpty());
    assertTrue(
        elapsedMillis >= 200,
        "blocking read must wait for the block duration, elapsed=" + elapsedMillis);
  }

  @Test
  void rejectsRecordsMissingEventFieldWithoutHidingTheFailure() {
    long threadId = 17L;
    template.opsForStream().add(CONFIG.key(threadId), Map.of("other", "not-an-event"));
    sink.append(modelDelta(threadId, "later"));
    assertThrows(
        IllegalArgumentException.class, () -> tail.readAfter(threadId, "0-0", 1, Duration.ZERO));
  }

  @Test
  void rejectsMalformedNonCanonicalOrExtraFieldRecords() {
    long malformedThread = 171L;
    template.opsForStream().add(CONFIG.key(malformedThread), Map.of("event", "{"));
    assertThrows(
        IllegalArgumentException.class,
        () -> tail.readAfter(malformedThread, "0-0", 10, Duration.ZERO));

    long nonCanonicalThread = 172L;
    String canonical = CODEC.encode(modelDelta(nonCanonicalThread, "valid"));
    template.opsForStream().add(CONFIG.key(nonCanonicalThread), Map.of("event", " " + canonical));
    assertThrows(
        IllegalArgumentException.class,
        () -> tail.readAfter(nonCanonicalThread, "0-0", 10, Duration.ZERO));

    long extraFieldThread = 173L;
    template
        .opsForStream()
        .add(
            CONFIG.key(extraFieldThread),
            Map.of("event", CODEC.encode(modelDelta(extraFieldThread, "valid")), "extra", "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> tail.readAfter(extraFieldThread, "0-0", 10, Duration.ZERO));
  }

  @Test
  void returnedListsAreImmutable() {
    appendModelDelta(18L, "one");
    List<Record> records = tail.readAfter(18L, "0-0", 10, Duration.ZERO);
    assertEquals(1, records.size());
    assertThrows(UnsupportedOperationException.class, () -> records.add(new Record("1-0", "{}")));
    assertThrows(UnsupportedOperationException.class, () -> records.remove(0));

    List<Record> empty = tail.readAfter(18L, tail.initialCursor(18L), 10, Duration.ZERO);
    assertTrue(empty.isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> empty.add(new Record("1-0", "{}")));
  }

  @Test
  void readAfterRejectsInvalidArguments() {
    appendModelDelta(19L, "one");
    assertThrows(
        IllegalArgumentException.class, () -> tail.readAfter(0L, "0-0", 10, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> tail.readAfter(19L, "0-0", 0, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> tail.readAfter(19L, "0-0", -1, Duration.ZERO));
    assertThrows(NullPointerException.class, () -> tail.readAfter(19L, "0-0", 10, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> tail.readAfter(19L, "0-0", 10, Duration.ofMillis(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> tail.readAfter(19L, "0-0", 10, Duration.ofNanos(1_500_000)));
    assertThrows(
        IllegalArgumentException.class, () -> tail.readAfter(19L, "bad-cursor", 10, Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> tail.readAfter(19L, "$", 10, Duration.ZERO));
  }

  private void appendModelDelta(long threadId, String text) {
    sink.append(modelDelta(threadId, text));
  }

  private static RealtimeEvent.ModelDelta modelDelta(long threadId, String text) {
    return new RealtimeEvent.ModelDelta(
        threadId, threadId * 100L, 1, 1L, new ProviderStreamEvent.TextDelta(text), NOW);
  }
}
