package fun.fengwk.kkstudio.share.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Outbox scheduling contract shared by every transient sender: whole-packet reservation until the
 * final native batch, fair rotation, bounded budgets, identity-fenced completion, deep redaction
 * and concurrent producers that cannot bypass the budget.
 */
class NotificationOutboxTest {
  private static final String TOPIC = "test.events";
  private final UUID self = UUID.randomUUID();

  @Test
  void emptyAndSingleFragmentPacketsUseOneBoundedFrame() {
    NotificationLimits limits = limits(100, 1000, 4, 4);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    NotificationPacket empty = packet(UUID.randomUUID(), self, "");
    NotificationPacket single = packet(UUID.randomUUID(), self, "hint");
    assertTrue(outbox.offer(empty));
    assertTrue(outbox.offer(single));
    assertEquals(2, outbox.pendingMessages());

    NotificationOutbox.Batch first = outbox.pollBatch().orElseThrow();
    assertEquals(empty.messageId(), first.messageId());
    assertEquals(1, first.frameCount());
    assertEquals(0, first.totalBytes());
    NotificationCarrier decodedEmpty =
        NotificationCarrier.decode(first.frames().get(0), limits, self);
    assertEquals(0, decodedEmpty.totalBytes());
    assertArrayEquals(new byte[0], decodedEmpty.bytes());
    assertTrue(outbox.complete(first, true));

    NotificationOutbox.Batch second = outbox.pollBatch().orElseThrow();
    assertEquals(single.messageId(), second.messageId());
    assertEquals(1, second.frameCount());
    assertEquals(4, second.totalBytes());
    assertTrue(outbox.complete(second, true));

    assertTrue(outbox.pollBatch().isEmpty());
    assertEquals(0, outbox.pendingMessages());
  }

  @Test
  void largePacketFragmentsRoundTripThroughTheSharedCarrier() {
    NotificationLimits limits = limits(40000, 40000, 8, 2);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    byte[] body = new byte[18000];
    for (int index = 0; index < body.length; index++) {
      body[index] = (byte) (index * 31);
    }
    NotificationPacket message =
        new NotificationPacket(UUID.randomUUID(), self, TOPIC, UUID.randomUUID(), body);
    assertTrue(outbox.offer(message));

    NotificationOutbox.Batch first = outbox.pollBatch().orElseThrow();
    assertEquals(2, first.frameCount());
    assertTrue(outbox.complete(first, true));
    NotificationOutbox.Batch second = outbox.pollBatch().orElseThrow();
    assertEquals(2, second.frameCount());
    assertTrue(outbox.complete(second, true));

    List<String> frames = new ArrayList<>(first.frames());
    frames.addAll(second.frames());
    assertEquals(NotificationCarrier.count(body.length), frames.size());
    NotificationPacket delivered = reassemble(frames, limits);
    assertEquals(message.messageId(), delivered.messageId());
    assertEquals(self, delivered.target());
    assertArrayEquals(body, delivered.bytes());
    assertEquals(0, outbox.pendingMessages());
  }

  @Test
  void unfinishedPacketRotatesToTailSoControlMessagePassesIt() {
    NotificationLimits limits = limits(40000, 40000, 8, 1);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    NotificationPacket large = packet(UUID.randomUUID(), self, "x".repeat(18000));
    NotificationPacket control = packet(UUID.randomUUID(), self, "control");
    assertEquals(4, NotificationCarrier.count(large.byteLength()));
    assertTrue(outbox.offer(large));
    assertTrue(outbox.offer(control));

    List<UUID> order = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      NotificationOutbox.Batch batch = outbox.pollBatch().orElseThrow();
      order.add(batch.messageId());
      assertTrue(outbox.complete(batch, true));
    }
    assertEquals(
        List.of(
            large.messageId(),
            control.messageId(),
            large.messageId(),
            large.messageId(),
            large.messageId()),
        order);
    assertEquals(0, outbox.pendingMessages());
  }

  @Test
  void reservationIsRetainedUntilTheFinalFragmentCompletes() {
    NotificationLimits limits = limits(40000, 40000, 8, 1);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    NotificationPacket large = packet(UUID.randomUUID(), self, "y".repeat(18000));
    assertTrue(outbox.offer(large));
    assertEquals(1, outbox.pendingMessages());
    assertEquals(18000, outbox.pendingBytes());

    NotificationOutbox.Batch first = outbox.pollBatch().orElseThrow();
    // The whole logical packet stays reserved while its first native batch is in flight.
    assertEquals(1, outbox.pendingMessages());
    assertEquals(18000, outbox.pendingBytes());
    assertTrue(outbox.complete(first, true));
    assertEquals(1, outbox.pendingMessages());
    assertEquals(18000, outbox.pendingBytes());

    for (int index = 0; index < 2; index++) {
      assertTrue(outbox.complete(outbox.pollBatch().orElseThrow(), true));
    }
    NotificationOutbox.Batch last = outbox.pollBatch().orElseThrow();
    assertEquals(1, outbox.pendingMessages());
    assertEquals(18000, outbox.pendingBytes(), "最后 native batch 未完成仍保留整包额度");
    assertTrue(outbox.complete(last, true));
    assertEquals(0, outbox.pendingMessages());
    assertEquals(0, outbox.pendingBytes());
  }

  @Test
  void queueByteAndPerMessageBudgetsRejectWithoutConsuming() {
    NotificationOutbox capacity = new NotificationOutbox(limits(100, 1000, 1, 1));
    assertTrue(capacity.offer(packet(UUID.randomUUID(), self, "a")));
    assertFalse(capacity.offer(packet(UUID.randomUUID(), self, "b")));
    assertEquals(1, capacity.pendingMessages());
    NotificationOutbox.Batch capacityActive = capacity.pollBatch().orElseThrow();
    assertFalse(capacity.offer(packet(UUID.randomUUID(), self, "c")), "在途计入项数限额");
    assertTrue(capacity.complete(capacityActive, true));
    assertTrue(capacity.offer(packet(UUID.randomUUID(), self, "c")));

    NotificationOutbox bytes = new NotificationOutbox(limits(100, 150, 4, 1));
    assertTrue(bytes.offer(packet(UUID.randomUUID(), self, "x".repeat(100))));
    assertFalse(bytes.offer(packet(UUID.randomUUID(), self, "y".repeat(100))));
    assertEquals(100, bytes.pendingBytes());
    NotificationOutbox.Batch bytesActive = bytes.pollBatch().orElseThrow();
    assertFalse(bytes.offer(packet(UUID.randomUUID(), self, "z".repeat(51))), "在途计入字节限额");
    assertTrue(bytes.complete(bytesActive, true));
    assertTrue(bytes.offer(packet(UUID.randomUUID(), self, "z".repeat(100))));

    NotificationOutbox perMessage = new NotificationOutbox(limits(50, 1000, 4, 1));
    assertFalse(perMessage.offer(packet(UUID.randomUUID(), self, "z".repeat(51))));
    assertEquals(0, perMessage.pendingMessages());
    assertThrows(NullPointerException.class, () -> perMessage.offer(null));
  }

  @Test
  void failedBatchDropsItsPacketAndReleasesTheReservationWithoutRetry() {
    NotificationLimits limits = limits(40000, 40000, 8, 1);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    NotificationPacket large = packet(UUID.randomUUID(), self, "f".repeat(18000));
    assertTrue(outbox.offer(large));
    NotificationOutbox.Batch first = outbox.pollBatch().orElseThrow();
    assertTrue(outbox.complete(first, false));
    assertEquals(0, outbox.pendingMessages());
    assertEquals(0, outbox.pendingBytes());
    assertTrue(outbox.pollBatch().isEmpty());
  }

  @Test
  void clearAndCloseReleaseEverythingAndGateFurtherOffers() {
    NotificationLimits limits = limits(40000, 40000, 8, 1);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    assertTrue(outbox.offer(packet(UUID.randomUUID(), self, "c".repeat(18000))));
    NotificationOutbox.Batch inFlight = outbox.pollBatch().orElseThrow();

    outbox.clear();
    assertEquals(0, outbox.pendingMessages());
    assertEquals(0, outbox.pendingBytes());
    // A late completion after clear is a no-op and cannot release another packet's budget.
    assertFalse(outbox.complete(inFlight, true));
    assertEquals(0, outbox.pendingMessages());
    // The outbox stays usable after clear.
    assertTrue(outbox.offer(packet(UUID.randomUUID(), self, "again")));
    assertTrue(outbox.complete(outbox.pollBatch().orElseThrow(), false));
    assertEquals(0, outbox.pendingMessages());

    outbox.close();
    assertFalse(outbox.offer(packet(UUID.randomUUID(), self, "closed")));
    assertTrue(outbox.pollBatch().isEmpty());
    assertEquals(0, outbox.pendingMessages());
  }

  @Test
  void foreignStaleAndDuplicateBatchesAreRejectedByIdentity() {
    NotificationLimits limits = limits(40000, 40000, 8, 2);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    NotificationOutbox foreignOutbox = new NotificationOutbox(limits);
    assertTrue(outbox.offer(packet(UUID.randomUUID(), self, "z".repeat(18000))));
    assertTrue(foreignOutbox.offer(packet(UUID.randomUUID(), self, "other".repeat(5000))));
    NotificationOutbox.Batch batch = outbox.pollBatch().orElseThrow();
    NotificationOutbox.Batch foreign = foreignOutbox.pollBatch().orElseThrow();

    // A batch from another outbox cannot release this outbox's active reservation.
    assertFalse(outbox.complete(foreign, true));
    assertFalse(outbox.complete(null, true));
    assertEquals(1, outbox.pendingMessages());
    assertEquals(18000, outbox.pendingBytes());

    assertTrue(outbox.complete(batch, true));
    assertFalse(outbox.complete(batch, true));
    assertFalse(outbox.complete(foreign, false));

    NotificationOutbox active = new NotificationOutbox(limits);
    assertTrue(active.offer(packet(UUID.randomUUID(), self, "s".repeat(18000))));
    assertTrue(active.pollBatch().isPresent());
    // At most one batch may be in flight.
    assertTrue(active.pollBatch().isEmpty());
  }

  @Test
  void batchFramesAreImmutableAndToStringRedactsBodyAndFrames() {
    NotificationLimits limits = limits(40000, 40000, 8, 2);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    String body = "secret-payload-" + "中😀".repeat(1000) + "-end";
    NotificationPacket message = packet(UUID.randomUUID(), self, body);
    assertTrue(outbox.offer(message));
    NotificationOutbox.Batch batch = outbox.pollBatch().orElseThrow();
    assertEquals(2, batch.frameCount());

    assertThrows(UnsupportedOperationException.class, () -> batch.frames().add("tampered"));
    assertThrows(UnsupportedOperationException.class, () -> batch.frames().clear());

    String text = batch.toString();
    assertFalse(text.contains("secret-payload"));
    assertFalse(text.contains(batch.frames().get(0)));
    assertTrue(text.contains(TOPIC));
    assertTrue(text.contains(message.messageId().toString()));
    assertEquals(message.publisher(), batch.publisher());
    assertEquals(self, batch.target());
    assertEquals(TOPIC, batch.topic());
    assertEquals(message.messageId(), batch.messageId());
    assertEquals(message.byteLength(), batch.totalBytes());
  }

  @Test
  void concurrentProducersCannotBypassTheQueueOrByteBudget() throws Exception {
    NotificationLimits limits = limits(20000, 20000, 8, 1);
    NotificationOutbox outbox = new NotificationOutbox(limits);
    int producers = 16;
    ExecutorService pool = Executors.newFixedThreadPool(producers);
    try {
      CountDownLatch start = new CountDownLatch(1);
      AtomicInteger accepted = new AtomicInteger();
      List<Future<Object>> futures = new ArrayList<>();
      for (int index = 0; index < producers; index++) {
        Callable<Object> offer =
            () -> {
              start.await();
              if (outbox.offer(packet(UUID.randomUUID(), self, "p".repeat(20000)))) {
                accepted.incrementAndGet();
              }
              return null;
            };
        futures.add(pool.submit(offer));
      }
      start.countDown();
      for (Future<Object> future : futures) {
        future.get();
      }
      // Byte budget admits exactly one 20000-byte packet even with spare queue capacity.
      assertEquals(1, accepted.get());
      assertEquals(1, outbox.pendingMessages());
      assertEquals(20000, outbox.pendingBytes());
      assertEquals(20000, outbox.pollBatch().orElseThrow().totalBytes());
    } finally {
      pool.shutdownNow();
    }
  }

  private NotificationPacket reassemble(List<String> frames, NotificationLimits limits) {
    AtomicReference<NotificationPacket> delivered = new AtomicReference<>();
    NotificationReassembler reassembler =
        new NotificationReassembler(self, limits, System::nanoTime, delivered::set, topic -> {});
    for (String frame : frames) {
      reassembler.accept(NotificationCarrier.decode(frame, limits, self));
    }
    return delivered.get();
  }

  private static NotificationLimits limits(
      int maxMessageBytes, int pendingBytes, int queueCapacity, int sendBatchFrames) {
    return new NotificationLimits(
        maxMessageBytes,
        pendingBytes,
        queueCapacity,
        Math.max(maxMessageBytes, pendingBytes),
        4,
        Duration.ofSeconds(5),
        sendBatchFrames);
  }

  private static NotificationPacket packet(UUID publisher, UUID target, String body) {
    return new NotificationPacket(
        publisher, target, TOPIC, UUID.randomUUID(), body.getBytes(StandardCharsets.UTF_8));
  }
}
