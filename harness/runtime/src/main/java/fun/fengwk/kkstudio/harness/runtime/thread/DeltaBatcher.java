package fun.fengwk.kkstudio.harness.runtime.thread;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 当前 Provider stream 的有界 Delta 缓冲；终态调用方必须 flush。 */
final class DeltaBatcher {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final long threadId;
  private final Long subjectEntryId;
  private final String processorToken;
  private final ThreadTransactions transactions;
  private final Clock clock;
  private final DeltaFlushScheduler flushScheduler;
  private final Duration interval;
  private final int maxBytes;
  private ArrayNode deltas = OBJECT_MAPPER.createArrayNode();
  private int bytes;
  private long batchGeneration;
  private Instant lastFlushAt;

  DeltaBatcher(
      long threadId,
      Long subjectEntryId,
      String processorToken,
      ThreadTransactions transactions,
      Clock clock,
      DeltaFlushScheduler flushScheduler,
      Duration interval,
      int maxBytes,
      Instant startedAt) {
    this.threadId = threadId;
    this.subjectEntryId = subjectEntryId;
    this.processorToken = Objects.requireNonNull(processorToken, "processorToken");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
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
      return;
    }
    ObjectNode payload = OBJECT_MAPPER.createObjectNode();
    payload.set("deltas", deltas);
    payload.put("schemaVersion", 1);
    String json;
    try {
      json = OBJECT_MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("cannot encode delta batch", error);
    }
    transactions.appendEvents(
        threadId,
        processorToken,
        List.of(new ThreadEventDraft(ThreadEventType.ASSISTANT_DELTA_BATCH, subjectEntryId, json)),
        now);
    deltas = OBJECT_MAPPER.createArrayNode();
    bytes = 0;
    lastFlushAt = now;
  }

  private static ObjectNode encode(ProviderStreamEvent event) {
    ObjectNode encoded = OBJECT_MAPPER.createObjectNode();
    if (event instanceof ProviderStreamEvent.TextDelta textDelta) {
      encoded.put("kind", "text");
      encoded.put("text", textDelta.text());
    } else if (event instanceof ProviderStreamEvent.ThinkingDelta thinkingDelta) {
      encoded.put("kind", "thinking");
      encoded.put("text", thinkingDelta.text());
    } else if (event instanceof ProviderStreamEvent.ToolCallDelta toolCallDelta) {
      encoded.put("kind", "tool_call");
      encoded.put("index", toolCallDelta.index());
      putNullable(encoded, "id", toolCallDelta.id());
      putNullable(encoded, "name", toolCallDelta.name());
      putNullable(encoded, "argumentsJson", toolCallDelta.argumentsJson());
    } else {
      throw new IllegalArgumentException("unsupported provider stream event: " + event.getClass());
    }
    return encoded;
  }

  private static void putNullable(ObjectNode node, String field, String value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value);
    }
  }
}
