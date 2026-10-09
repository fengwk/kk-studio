package fun.fengwk.kkstudio.share.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reassembly contract shared by every transport: out-of-order delivery, bounded budgets, expiry,
 * conflicting fragments, self-echo and two independent receivers.
 */
class NotificationReassemblerTest {
  private static final String TOPIC = "test.events";
  private final UUID self = UUID.randomUUID();
  private final AtomicLong clock = new AtomicLong();
  private final List<NotificationPacket> delivered = new ArrayList<>();
  private final List<String> resyncs = new ArrayList<>();
  private final NotificationReassembler assembler =
      new NotificationReassembler(self, limits(3), clock::get, delivered::add, resyncs::add);

  @Test
  void reorderedIdenticalDuplicatesDeliverOneCompleteMessage() {
    NotificationPacket message = packet(UUID.randomUUID(), self, "中😀".repeat(2000));
    assertEquals(3, NotificationCarrier.count(message.byteLength()));
    assembler.accept(NotificationCarrier.chunk(message, 2));
    assembler.accept(NotificationCarrier.chunk(message, 0));
    assembler.accept(NotificationCarrier.chunk(message, 0));
    assertTrue(delivered.isEmpty());
    assembler.accept(NotificationCarrier.chunk(message, 1));
    for (int index = 0; index < 3; index++) {
      assembler.accept(NotificationCarrier.chunk(message, index));
    }
    assertEquals(1, delivered.size());
    assertArrayEquals(message.bytes(), delivered.getFirst().bytes());
    assertEquals(self, delivered.getFirst().target());
    assertEquals(0, assembler.reservedBytes());
    assertTrue(resyncs.isEmpty());
  }

  @Test
  void countOneAndEmptyMessagesFollowSameReassemblyPath() {
    assembler.accept(NotificationCarrier.chunk(packet(UUID.randomUUID(), null, ""), 0));
    assembler.accept(NotificationCarrier.chunk(packet(UUID.randomUUID(), null, "hint"), 0));
    assertEquals(2, delivered.size());
    assertEquals(0, assembler.reservedBytes());
  }

  @Test
  void conflictingDuplicateAndHeaderDiscardEntireLogicalMessage() {
    NotificationPacket message = packet(UUID.randomUUID(), null, "a".repeat(10000));
    NotificationCarrier first = NotificationCarrier.chunk(message, 0);
    assembler.accept(first);
    byte[] conflict = first.bytes().clone();
    conflict[0] = 2;
    assembler.accept(
        new NotificationCarrier(
            first.publisher(),
            first.target(),
            first.topic(),
            first.messageId(),
            0,
            first.count(),
            first.totalBytes(),
            conflict));
    assembler.accept(NotificationCarrier.chunk(message, 1));
    assertTrue(delivered.isEmpty());
    assertEquals(List.of(TOPIC), resyncs);
    assertEquals(0, assembler.reservedBytes());

    NotificationPacket second = packet(UUID.randomUUID(), null, "b".repeat(10000));
    NotificationCarrier original = NotificationCarrier.chunk(second, 0);
    assembler.accept(original);
    assembler.accept(
        new NotificationCarrier(
            original.publisher(),
            self,
            "other.topic",
            original.messageId(),
            1,
            2,
            original.totalBytes(),
            NotificationCarrier.chunk(second, 1).bytes()));
    assertEquals(3, resyncs.size());
    assertEquals(0, assembler.reservedBytes());
  }

  @Test
  void missingChunksExpireWithoutApplyingPartialPayload() {
    NotificationPacket message = packet(UUID.randomUUID(), null, "x".repeat(10000));
    assembler.accept(NotificationCarrier.chunk(message, 0));
    assertEquals(10000, assembler.reservedBytes());
    clock.set(limits(3).reassemblyTimeout().toNanos());
    assembler.expire();
    assembler.accept(NotificationCarrier.chunk(message, 1));
    assertEquals(List.of(TOPIC), resyncs);
    assertTrue(delivered.isEmpty());
    assertEquals(0, assembler.reservedBytes());
    clock.addAndGet(limits(3).reassemblyTimeout().toNanos() + 1);
    assembler.expire();
    assembler.clear();
  }

  @Test
  void boundedMessagesAndBytesRejectAndReleaseWhileEchoesAllocateNothing() {
    for (int index = 0; index < 3; index++) {
      assembler.accept(
          NotificationCarrier.chunk(packet(UUID.randomUUID(), null, "x".repeat(10000)), 0));
    }
    assertEquals(20000, assembler.reservedBytes());
    assertEquals(1, resyncs.size());
    assembler.accept(NotificationCarrier.chunk(packet(self, null, "echo".repeat(2000)), 0));
    assembler.accept(
        NotificationCarrier.chunk(packet(UUID.randomUUID(), UUID.randomUUID(), "foreign"), 0));
    assertEquals(20000, assembler.reservedBytes());
    assembler.clear();
    assertEquals(0, assembler.reservedBytes());

    NotificationLimits byteLimits =
        new NotificationLimits(20000, 40000, 2, 20000, 8, limits(3).reassemblyTimeout(), 1);
    NotificationReassembler byteBound =
        new NotificationReassembler(self, byteLimits, clock::get, delivered::add, resyncs::add);
    byteBound.accept(
        NotificationCarrier.chunk(packet(UUID.randomUUID(), null, "a".repeat(15000)), 0));
    byteBound.accept(
        NotificationCarrier.chunk(packet(UUID.randomUUID(), null, "b".repeat(15000)), 0));
    assertEquals(15000, byteBound.reservedBytes());
    assertEquals(2, resyncs.size());
  }

  @Test
  void handBuiltOverBudgetCarrierIsRejectedWithoutInflatingAllocation() {
    // accept must reject an over-budget carrier up front instead of trusting the caller's carrier.
    NotificationCarrier chunk =
        NotificationCarrier.chunk(packet(UUID.randomUUID(), null, "z".repeat(60000)), 0);
    NotificationLimits tight =
        new NotificationLimits(20000, 40000, 2, 20000, 8, Duration.ofSeconds(5), 1);
    NotificationReassembler bounded =
        new NotificationReassembler(self, tight, clock::get, delivered::add, resyncs::add);
    bounded.accept(chunk);
    assertEquals(List.of(TOPIC), resyncs);
    assertEquals(0, bounded.reservedBytes());
    assertTrue(delivered.isEmpty());
  }

  @Test
  void logicalLimitAppliesEvenWhenAggregateReassemblyBudgetHasRoom() {
    // Aggregate room cannot authorize a body larger than the per-message budget.
    NotificationPacket message = packet(UUID.randomUUID(), self, "z".repeat(30000));
    for (int index = 0; index < NotificationCarrier.count(message.byteLength()); index++) {
      assembler.accept(NotificationCarrier.chunk(message, index));
    }
    assertEquals(List.of(TOPIC), resyncs);
    assertEquals(0, assembler.reservedBytes());
    assertTrue(delivered.isEmpty());
  }

  @Test
  void twoIndependentReceivingIdentitiesShareTheSamePrimitive() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    List<NotificationPacket> deliveredFirst = new ArrayList<>();
    List<NotificationPacket> deliveredSecond = new ArrayList<>();
    NotificationReassembler reassemblerFirst =
        new NotificationReassembler(
            first, limits(8), clock::get, deliveredFirst::add, resyncs::add);
    NotificationReassembler reassemblerSecond =
        new NotificationReassembler(
            second, limits(8), clock::get, deliveredSecond::add, resyncs::add);

    // A directed message reaches only its addressed receiver once the bytes are complete.
    NotificationPacket directed = packet(UUID.randomUUID(), second, "directed".repeat(2000));
    for (int index = 0; index < NotificationCarrier.count(directed.byteLength()); index++) {
      String encoded = NotificationCarrier.chunk(directed, index).encode();
      reassemblerFirst.accept(NotificationCarrier.decode(encoded, limits(8), first));
      reassemblerSecond.accept(NotificationCarrier.decode(encoded, limits(8), second));
    }
    assertTrue(deliveredFirst.isEmpty());
    assertEquals(1, deliveredSecond.size());
    assertArrayEquals(directed.bytes(), deliveredSecond.getFirst().bytes());

    // A broadcast reaches both receivers through the same primitive.
    NotificationPacket broadcast = packet(UUID.randomUUID(), null, "broadcast".repeat(1000));
    for (int index = 0; index < NotificationCarrier.count(broadcast.byteLength()); index++) {
      String encoded = NotificationCarrier.chunk(broadcast, index).encode();
      reassemblerFirst.accept(NotificationCarrier.decode(encoded, limits(8), first));
      reassemblerSecond.accept(NotificationCarrier.decode(encoded, limits(8), second));
    }
    assertEquals(1, deliveredFirst.size());
    assertEquals(2, deliveredSecond.size());

    // The publisher never consumes its own echo, even a corrupted one.
    NotificationPacket own = packet(first, null, "own".repeat(1000));
    String encoded = NotificationCarrier.chunk(own, 0).encode();
    assertNull(
        NotificationCarrier.decode(
            encoded.substring(0, encoded.lastIndexOf('|') + 1) + "bad", limits(8), first));
  }

  private static NotificationPacket packet(UUID publisher, UUID target, String payload) {
    return new NotificationPacket(
        publisher, target, TOPIC, UUID.randomUUID(), payload.getBytes(StandardCharsets.UTF_8));
  }

  private static NotificationLimits limits(int capacity) {
    return new NotificationLimits(20000, 40000, capacity, 40000, 2, Duration.ofSeconds(5), 1);
  }
}
