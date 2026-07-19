package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Guards the exact Provider delta JSON handed to the durable Thread event transaction. */
class DeltaBatcherTest {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

  @Test
  void encodesEveryProviderDeltaSubtypeWithAStableKind() throws Exception {
    AtomicReference<List<ThreadEventDraft>> appendedEvents = new AtomicReference<>();
    DeltaBatcher batcher =
        new DeltaBatcher(
            1L,
            2L,
            "processor-token",
            transactions(appendedEvents::set),
            Clock.fixed(NOW, ZoneOffset.UTC),
            (delay, task) -> {},
            Duration.ofMillis(50),
            16 * 1024,
            NOW);

    batcher.add(new ProviderStreamEvent.ThinkingDelta("plan"));
    batcher.add(new ProviderStreamEvent.TextDelta("answer"));
    batcher.add(new ProviderStreamEvent.ToolCallDelta(3, "call-1", "bash", null));
    batcher.flush();

    List<ThreadEventDraft> events = appendedEvents.get();
    assertNotNull(events);
    assertEquals(1, events.size());
    ThreadEventDraft event = events.get(0);
    assertEquals(ThreadEventType.ASSISTANT_DELTA_BATCH, event.type());
    assertEquals(2L, event.subjectEntryId());
    JsonNode payload = OBJECT_MAPPER.readTree(event.payloadJson());
    assertEquals(1, payload.get("schemaVersion").asInt());
    JsonNode deltas = payload.get("deltas");
    assertEquals(3, deltas.size());
    assertEquals("thinking", deltas.get(0).get("kind").asText());
    assertEquals("plan", deltas.get(0).get("text").asText());
    assertEquals("text", deltas.get(1).get("kind").asText());
    assertEquals("answer", deltas.get(1).get("text").asText());
    assertEquals("tool_call", deltas.get(2).get("kind").asText());
    assertEquals(3, deltas.get(2).get("index").asInt());
    assertEquals("call-1", deltas.get(2).get("id").asText());
    assertEquals("bash", deltas.get(2).get("name").asText());
    assertTrue(deltas.get(2).get("argumentsJson").isNull());
  }

  @Test
  void flushesImmediatelyWhenTheEncodedBatchReachesTheByteLimit() {
    AtomicInteger appendCount = new AtomicInteger();
    DeltaBatcher batcher =
        new DeltaBatcher(
            1L,
            null,
            "processor-token",
            transactions(events -> appendCount.incrementAndGet()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            (delay, task) -> {},
            Duration.ofSeconds(1),
            1,
            NOW);

    batcher.add(new ProviderStreamEvent.TextDelta("answer"));

    assertEquals(1, appendCount.get());
  }

  @Test
  void scheduledFlushDoesNotAppendAnEmptyBatchWhenInvokedAgain() {
    AtomicInteger appendCount = new AtomicInteger();
    AtomicReference<Runnable> scheduledFlush = new AtomicReference<>();
    DeltaBatcher batcher =
        new DeltaBatcher(
            1L,
            null,
            "processor-token",
            transactions(events -> appendCount.incrementAndGet()),
            Clock.fixed(NOW, ZoneOffset.UTC),
            (delay, task) -> scheduledFlush.set(task),
            Duration.ofMillis(50),
            16 * 1024,
            NOW);
    batcher.add(new ProviderStreamEvent.ThinkingDelta("plan"));

    assertNotNull(scheduledFlush.get());
    scheduledFlush.get().run();
    scheduledFlush.get().run();

    assertEquals(1, appendCount.get());
  }

  private static ThreadTransactions transactions(Consumer<List<ThreadEventDraft>> appendEvents) {
    return (ThreadTransactions)
        Proxy.newProxyInstance(
            ThreadTransactions.class.getClassLoader(),
            new Class<?>[] {ThreadTransactions.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("appendEvents")) {
                @SuppressWarnings("unchecked")
                List<ThreadEventDraft> events = (List<ThreadEventDraft>) arguments[2];
                appendEvents.accept(events);
                return true;
              }
              throw new AssertionError("unexpected transaction call: " + method.getName());
            });
  }
}
