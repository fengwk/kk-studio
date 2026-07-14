package fun.fengwk.kkstudio.harness.runtime.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class DeltaBatcherTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  /** 小 Delta 到 150ms 边界批量写，显式 terminal flush 清空剩余且不会重复。 */
  @Test
  void flushesByTimeAndAtTerminal() {
    MutableClock clock = new MutableClock();
    RecordingEventStore events = new RecordingEventStore();
    ManualScheduler scheduler = new ManualScheduler();
    DeltaBatcher batcher =
        new DeltaBatcher(1L, 1, 0, events, clock, scheduler, Duration.ofMillis(150), 8 * 1024, NOW);

    batcher.add(new ProviderStreamEvent.TextDelta("a"));
    assertTrue(events.events.isEmpty());
    clock.advance(Duration.ofMillis(150));
    scheduler.runNext();
    assertEquals(1, events.events.size());
    assertTrue(events.events.get(0).payloadJson().contains("text"));
    batcher.add(new ProviderStreamEvent.ThinkingDelta("b"));
    batcher.add(new ProviderStreamEvent.ToolCallDelta(0, "id", "read", "{}"));
    batcher.flush();
    batcher.flush();

    assertEquals(2, events.events.size());
    assertTrue(events.events.get(1).payloadJson().contains("tool_call"));
  }

  /** 单个 8KiB 以上 Delta 立即按容量 flush，不等待 timer。 */
  @Test
  void flushesBySize() {
    MutableClock clock = new MutableClock();
    RecordingEventStore events = new RecordingEventStore();
    DeltaBatcher batcher =
        new DeltaBatcher(
            1L, 1, 0, events, clock, (delay, task) -> {}, Duration.ofMillis(250), 8 * 1024, NOW);

    batcher.add(new ProviderStreamEvent.TextDelta("x".repeat(9 * 1024)));

    assertEquals(1, events.events.size());
  }

  /** reclaim 后旧 stream 的晚到 Delta 保留 attempt=1，新 stream 明确标记 attempt=2。 */
  @Test
  void keepsLateAttemptDeltaScopedAfterReclaim() {
    MutableClock clock = new MutableClock();
    RecordingEventStore events = new RecordingEventStore();
    DeltaBatcher attemptOne =
        new DeltaBatcher(
            1L, 1, 0, events, clock, (delay, task) -> {}, Duration.ofMillis(150), 8 * 1024, NOW);
    DeltaBatcher attemptTwo =
        new DeltaBatcher(
            1L, 2, 0, events, clock, (delay, task) -> {}, Duration.ofMillis(150), 8 * 1024, NOW);

    attemptTwo.add(new ProviderStreamEvent.TextDelta("current"));
    attemptTwo.flush();
    attemptOne.add(new ProviderStreamEvent.TextDelta("late"));
    attemptOne.flush();

    assertTrue(events.events.get(0).payloadJson().contains("\"attempt\":2"));
    assertTrue(events.events.get(0).payloadJson().contains("\"turnIndex\":0"));
    assertTrue(events.events.get(1).payloadJson().contains("\"attempt\":1"));
    assertTrue(events.events.get(1).payloadJson().contains("late"));
  }

  private static final class ManualScheduler implements DeltaFlushScheduler {
    private final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void schedule(Duration delay, Runnable flushTask) {
      tasks.add(flushTask);
    }

    void runNext() {
      tasks.remove().run();
    }
  }

  private static final class RecordingEventStore implements RunEventStore {
    private final List<RunEvent> events = new ArrayList<>();

    @Override
    public RunEvent append(long runId, RunEventType type, String payloadJson, Instant createdAt) {
      RunEvent event =
          new RunEvent(events.size() + 1L, runId, events.size() + 1L, type, payloadJson, createdAt);
      events.add(event);
      return event;
    }

    @Override
    public List<RunEvent> listAfter(long runId, long afterSequence, int limit) {
      return events.stream()
          .filter(event -> event.sequence() > afterSequence)
          .limit(limit)
          .toList();
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = NOW;

    void advance(Duration duration) {
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
