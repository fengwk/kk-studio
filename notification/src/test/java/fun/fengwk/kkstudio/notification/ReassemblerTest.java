package fun.fengwk.kkstudio.notification;

import static fun.fengwk.kkstudio.notification.NotificationTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

class ReassemblerTest {
  private final UUID self = UUID.randomUUID();
  private final AtomicLong clock = new AtomicLong();
  private final List<WireMessage> delivered = new ArrayList<>();
  private final List<String> resyncs = new ArrayList<>();
  private final Reassembler assembler =
      new Reassembler(self, smallLimits(3), clock::get, delivered::add, resyncs::add);

  @Test
  void reorderedIdenticalDuplicatesDeliverOneCompleteMessage() {
    WireMessage message = wire(UUID.randomUUID(), self, "中😀".repeat(2000));
    assertEquals(3, Carrier.count(message.bytes().length));
    assembler.accept(Carrier.chunk(message, 2));
    assembler.accept(Carrier.chunk(message, 0));
    assembler.accept(Carrier.chunk(message, 0));
    assertTrue(delivered.isEmpty());
    assembler.accept(Carrier.chunk(message, 1));
    for (int index = 0; index < 3; index++) {
      assembler.accept(Carrier.chunk(message, index));
    }
    assertEquals(1, delivered.size());
    assertArrayEquals(message.bytes(), delivered.getFirst().bytes());
    assertEquals(0, assembler.reservedBytes());
    assertTrue(resyncs.isEmpty());
  }

  @Test
  void countOneAndEmptyMessagesFollowSameReassemblyPath() {
    assembler.accept(Carrier.chunk(wire(UUID.randomUUID(), null, ""), 0));
    assembler.accept(Carrier.chunk(wire(UUID.randomUUID(), null, "hint"), 0));
    assertEquals(2, delivered.size());
    assertEquals(0, assembler.reservedBytes());
  }

  @Test
  void conflictingDuplicateAndHeaderDiscardEntireLogicalMessage() {
    WireMessage message = wire(UUID.randomUUID(), null, "a".repeat(10000));
    Carrier first = Carrier.chunk(message, 0);
    assembler.accept(first);
    byte[] conflict = first.bytes().clone();
    conflict[0] = 2;
    assembler.accept(
        new Carrier(
            first.publisher(),
            first.target(),
            first.topic(),
            first.messageId(),
            0,
            first.count(),
            first.totalBytes(),
            conflict));
    assembler.accept(Carrier.chunk(message, 1));
    assertTrue(delivered.isEmpty());
    assertEquals(List.of(EVENTS.name()), resyncs);
    assertEquals(0, assembler.reservedBytes());

    WireMessage second = wire(UUID.randomUUID(), null, "b".repeat(10000));
    Carrier original = Carrier.chunk(second, 0);
    assembler.accept(original);
    assembler.accept(
        new Carrier(
            original.publisher(),
            self,
            "other.topic",
            original.messageId(),
            1,
            2,
            original.totalBytes(),
            Carrier.chunk(second, 1).bytes()));
    assertEquals(3, resyncs.size());
    assertEquals(0, assembler.reservedBytes());
  }

  @Test
  void missingChunksExpireWithoutApplyingPartialPayload() {
    WireMessage message = wire(UUID.randomUUID(), null, "x".repeat(10000));
    assembler.accept(Carrier.chunk(message, 0));
    assertEquals(10000, assembler.reservedBytes());
    clock.set(smallLimits(3).reassemblyTimeout().toNanos());
    assembler.expire();
    assembler.accept(Carrier.chunk(message, 1));
    assertEquals(List.of(EVENTS.name()), resyncs);
    assertTrue(delivered.isEmpty());
    assertEquals(0, assembler.reservedBytes());
    clock.addAndGet(smallLimits(3).reassemblyTimeout().toNanos() + 1);
    assembler.expire();
    assembler.clear();
  }

  @Test
  void boundedMessagesAndBytesRejectAndReleaseWhileEchoesAllocateNothing() {
    for (int i = 0; i < 3; i++) {
      assembler.accept(Carrier.chunk(wire(UUID.randomUUID(), null, "x".repeat(10000)), 0));
    }
    assertEquals(20000, assembler.reservedBytes());
    assertEquals(1, resyncs.size());
    assembler.accept(Carrier.chunk(wire(self, null, "echo".repeat(2000)), 0));
    assembler.accept(Carrier.chunk(wire(UUID.randomUUID(), UUID.randomUUID(), "foreign"), 0));
    assertEquals(20000, assembler.reservedBytes());
    assembler.clear();
    assertEquals(0, assembler.reservedBytes());

    NotificationLimits byteLimits =
        new NotificationLimits(20000, 40000, 2, 20000, 8, smallLimits(3).reassemblyTimeout(), 1);
    Reassembler byteBound =
        new Reassembler(self, byteLimits, clock::get, delivered::add, resyncs::add);
    byteBound.accept(Carrier.chunk(wire(UUID.randomUUID(), null, "a".repeat(15000)), 0));
    byteBound.accept(Carrier.chunk(wire(UUID.randomUUID(), null, "b".repeat(15000)), 0));
    assertEquals(15000, byteBound.reservedBytes());
    assertEquals(2, resyncs.size());
  }
}
