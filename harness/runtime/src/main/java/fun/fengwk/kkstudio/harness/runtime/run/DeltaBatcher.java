package fun.fengwk.kkstudio.harness.runtime.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** 当前 Provider stream 的有界 Delta 缓冲；终态调用方必须 flush。 */
final class DeltaBatcher {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final long runId;
  private final int attempt;
  private final int turnIndex;
  private final RunEventStore eventStore;
  private final Clock clock;
  private final DeltaFlushScheduler flushScheduler;
  private final Duration interval;
  private final int maxBytes;
  private ArrayNode deltas = OBJECT_MAPPER.createArrayNode();
  private int bytes;
  private long batchGeneration;
  private Instant lastFlushAt;

  DeltaBatcher(
      long runId,
      int attempt,
      int turnIndex,
      RunEventStore eventStore,
      Clock clock,
      DeltaFlushScheduler flushScheduler,
      Duration interval,
      int maxBytes,
      Instant startedAt) {
    this.runId = runId;
    if (attempt <= 0 || turnIndex < 0) {
      throw new IllegalArgumentException("attempt must be positive and turnIndex non-negative");
    }
    this.attempt = attempt;
    this.turnIndex = turnIndex;
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.flushScheduler = Objects.requireNonNull(flushScheduler, "flushScheduler");
    this.interval = Objects.requireNonNull(interval, "interval");
    this.maxBytes = maxBytes;
    this.lastFlushAt = Objects.requireNonNull(startedAt, "startedAt");
  }

  synchronized void add(ProviderStreamEvent event) {
    boolean newBatch = deltas.isEmpty();
    ObjectNode encoded = encode(event);
    deltas.add(encoded);
    bytes += encoded.toString().getBytes(StandardCharsets.UTF_8).length;
    if (newBatch) {
      long generation = ++batchGeneration;
      flushScheduler.schedule(interval, () -> flushGeneration(generation));
    }
    Instant now = clock.instant();
    if (bytes >= maxBytes || !now.isBefore(lastFlushAt.plus(interval))) {
      flushAt(now);
    }
  }

  synchronized void flush() {
    flushAt(clock.instant());
  }

  private synchronized void flushGeneration(long generation) {
    if (generation == batchGeneration) {
      flushAt(clock.instant());
    }
  }

  private void flushAt(Instant now) {
    if (deltas.isEmpty()) {
      lastFlushAt = now;
      return;
    }
    ObjectNode payload = OBJECT_MAPPER.createObjectNode();
    payload.put("schemaVersion", 1);
    payload.put("attempt", attempt);
    payload.put("turnIndex", turnIndex);
    payload.set("deltas", deltas);
    try {
      eventStore.append(
          runId,
          RunEventType.ASSISTANT_DELTA_BATCH,
          OBJECT_MAPPER.writeValueAsString(payload),
          now);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode delta batch", error);
    }
    deltas = OBJECT_MAPPER.createArrayNode();
    bytes = 0;
    lastFlushAt = now;
  }

  private ObjectNode encode(ProviderStreamEvent event) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    if (event instanceof ProviderStreamEvent.TextDelta value) {
      node.put("kind", "text");
      node.put("text", value.text());
    } else if (event instanceof ProviderStreamEvent.ThinkingDelta value) {
      node.put("kind", "thinking");
      node.put("text", value.text());
    } else if (event instanceof ProviderStreamEvent.ToolCallDelta value) {
      node.put("kind", "tool_call");
      node.put("index", value.index());
      node.put("id", value.id());
      node.put("name", value.name());
      node.put("argumentsJson", value.argumentsJson());
    } else {
      throw new IllegalArgumentException("unknown provider delta: " + event.getClass());
    }
    return node;
  }
}
