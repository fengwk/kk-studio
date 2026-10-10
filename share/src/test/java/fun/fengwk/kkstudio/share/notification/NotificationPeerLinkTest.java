package fun.fengwk.kkstudio.share.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Peer framing contract shared by the Daemon client and the environment daemon endpoint: the single
 * carrier path (including {@code count=1}), peer/topic/target freeze, reassembly release and the
 * one close path.
 */
class NotificationPeerLinkTest {

  private static final String TOPIC = NotificationPeerLink.DAEMON_TOPIC;

  private static NotificationLimits limits(int maxMessageBytes, int sendBatchFrames) {
    return new NotificationLimits(
        maxMessageBytes, 64 * 1024, 8, 64 * 1024, 4, Duration.ofMillis(50), sendBatchFrames);
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static final class Recorder {
    final List<String> messages = new ArrayList<>();
    final AtomicInteger closeRequests = new AtomicInteger();

    NotificationPeerLink link(UUID self, NotificationLimits limits) {
      return new NotificationPeerLink(
          self, TOPIC, limits, null, messages::add, closeRequests::incrementAndGet);
    }
  }

  private static void drain(NotificationPeerLink from, NotificationPeerLink to) {
    Optional<NotificationOutbox.Batch> batch;
    while ((batch = from.pollBatch()).isPresent()) {
      for (String frame : batch.get().frames()) {
        to.accept(frame);
      }
      from.complete(batch.get(), true);
    }
  }

  /** A single short message still travels as one carrier fragment and round-trips exactly. */
  @Test
  void countOneAlsoUsesTheCarrier() {
    Recorder daemon = new Recorder();
    Recorder server = new Recorder();
    NotificationPeerLink up = daemon.link(UUID.randomUUID(), limits(16000, 32));
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(16000, 32));

    assertTrue(up.offer(UUID.randomUUID(), "HELLO"));
    NotificationOutbox.Batch batch = up.pollBatch().orElseThrow();
    assertEquals(1, batch.frameCount());
    for (String frame : batch.frames()) {
      down.accept(frame);
    }
    up.complete(batch, true);

    assertEquals(List.of("HELLO"), server.messages);
    assertEquals(0, server.closeRequests.get());
  }

  /** A body larger than one chunk reassembles across multiple bounded batches. */
  @Test
  void fragmentsReassembleAcrossBatches() {
    Recorder daemon = new Recorder();
    Recorder server = new Recorder();
    NotificationPeerLink up = daemon.link(UUID.randomUUID(), limits(20000, 1));
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(20000, 1));

    String body = "A".repeat(13000);
    assertTrue(up.offer(UUID.randomUUID(), body));

    int batches = 0;
    Optional<NotificationOutbox.Batch> batch;
    while ((batch = up.pollBatch()).isPresent()) {
      assertEquals(1, batch.get().frameCount());
      for (String frame : batch.get().frames()) {
        down.accept(frame);
      }
      up.complete(batch.get(), true);
      batches++;
    }
    assertEquals(NotificationCarrier.count(utf8(body).length), batches);
    assertEquals(List.of(body), server.messages);
  }

  /** A large body rotates so a small control message queued behind it is not starved. */
  @Test
  void largeBodyRotatesSoSmallControlIsNotStarved() {
    Recorder daemon = new Recorder();
    Recorder server = new Recorder();
    NotificationPeerLink up = daemon.link(UUID.randomUUID(), limits(20000, 1));
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(20000, 1));

    assertTrue(up.offer(UUID.randomUUID(), "R".repeat(13000)));
    assertTrue(up.offer(UUID.randomUUID(), "small"));

    NotificationOutbox.Batch first = up.pollBatch().orElseThrow();
    assertEquals(13000, first.totalBytes());
    for (String frame : first.frames()) {
      down.accept(frame);
    }
    up.complete(first, true);

    NotificationOutbox.Batch second = up.pollBatch().orElseThrow();
    assertEquals(utf8("small").length, second.totalBytes());
    for (String frame : second.frames()) {
      down.accept(frame);
    }
    up.complete(second, true);

    assertEquals(List.of("small"), server.messages);
  }

  /**
   * The first valid fragment freezes the peer; a later different publisher is a fatal violation.
   */
  @Test
  void freezesPeerAndRejectsADifferentPublisher() {
    Recorder daemon = new Recorder();
    Recorder server = new Recorder();
    UUID daemonId = UUID.randomUUID();
    NotificationPeerLink up = daemon.link(daemonId, limits(16000, 32));
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(16000, 32));

    assertTrue(up.offer(UUID.randomUUID(), "one"));
    drain(up, down);
    assertEquals(daemonId, down.peer());
    assertEquals(List.of("one"), server.messages);

    NotificationPeerLink impostor = new Recorder().link(UUID.randomUUID(), limits(16000, 32));
    assertTrue(impostor.offer(UUID.randomUUID(), "two"));
    drain(impostor, down);

    assertEquals(1, server.closeRequests.get());
    assertTrue(down.isClosed());
  }

  /** A wrong topic or a foreign target is a deterministic violation. */
  @Test
  void rejectsWrongTopicAndForeignTarget() {
    Recorder server = new Recorder();
    UUID self = UUID.randomUUID();
    NotificationPeerLink down = server.link(self, limits(16000, 32));

    NotificationPacket foreignTopic =
        new NotificationPacket(
            UUID.randomUUID(), null, "other.topic", UUID.randomUUID(), utf8("x"));
    down.accept(NotificationCarrier.chunk(foreignTopic, 0).encode());
    assertEquals(1, server.closeRequests.get());
    assertTrue(down.isClosed());

    Recorder second = new Recorder();
    NotificationPeerLink target = second.link(self, limits(16000, 32));
    NotificationPacket foreignTarget =
        new NotificationPacket(
            UUID.randomUUID(), UUID.randomUUID(), TOPIC, UUID.randomUUID(), utf8("x"));
    target.accept(NotificationCarrier.chunk(foreignTarget, 0).encode());
    assertEquals(1, second.closeRequests.get());
    assertTrue(target.isClosed());
  }

  /** A legacy raw JSON frame is not a carrier and closes the link instead of being delivered. */
  @Test
  void rejectsLegacyRawJson() {
    Recorder server = new Recorder();
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(16000, 32));

    down.accept("{\"version\":3,\"messageType\":\"HELLO\"}");

    assertTrue(server.messages.isEmpty());
    assertEquals(1, server.closeRequests.get());
    assertTrue(down.isClosed());
  }

  /** An own echo is dropped before any reassembly and never counts as a peer. */
  @Test
  void dropsOwnEchoWithoutFreezingPeer() {
    Recorder daemon = new Recorder();
    UUID daemonId = UUID.randomUUID();
    NotificationPeerLink up = daemon.link(daemonId, limits(16000, 32));

    NotificationPacket echo =
        new NotificationPacket(daemonId, null, TOPIC, UUID.randomUUID(), utf8("self"));
    up.accept(NotificationCarrier.chunk(echo, 0).encode());

    assertNull(up.peer());
    assertEquals(0, daemon.closeRequests.get());
    assertFalse(up.isClosed());
  }

  /** A byte-identical duplicate is ignored; a conflicting duplicate releases the whole message. */
  @Test
  void duplicateFragmentsAreIgnoredOrRejected() {
    Recorder daemon = new Recorder();
    Recorder server = new Recorder();
    UUID publisher = UUID.randomUUID();
    NotificationPeerLink up = daemon.link(publisher, limits(20000, 2));
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(20000, 2));

    UUID messageId = UUID.randomUUID();
    assertTrue(up.offer(messageId, "B".repeat(7000)));
    NotificationOutbox.Batch batch = up.pollBatch().orElseThrow();
    String firstFrame = batch.frames().get(0);
    up.complete(batch, true);

    down.accept(firstFrame);
    down.accept(firstFrame); // identical duplicate: ignored, no resync
    assertEquals(0, server.closeRequests.get());

    NotificationCarrier original =
        NotificationCarrier.chunk(
            new NotificationPacket(publisher, null, TOPIC, messageId, utf8("B".repeat(7000))), 0);
    byte[] conflictingBytes = original.bytes();
    conflictingBytes[0] = (byte) (conflictingBytes[0] ^ 0xFF);
    NotificationCarrier conflicting =
        new NotificationCarrier(
            publisher,
            null,
            TOPIC,
            messageId,
            0,
            NotificationCarrier.count(utf8("B".repeat(7000)).length),
            utf8("B".repeat(7000)).length,
            conflictingBytes);
    down.accept(conflicting.encode());

    assertEquals(1, server.closeRequests.get());
    assertTrue(down.isClosed());
  }

  /** A missing fragment expires and funnels into the single close path. */
  @Test
  void expiredMessageRequestsClose() throws Exception {
    Recorder daemon = new Recorder();
    Recorder server = new Recorder();
    NotificationPeerLink up = daemon.link(UUID.randomUUID(), limits(20000, 1));
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(20000, 1));

    assertTrue(up.offer(UUID.randomUUID(), "C".repeat(7000)));
    NotificationOutbox.Batch first = up.pollBatch().orElseThrow();
    up.complete(first, true);
    down.accept(first.frames().get(0));
    assertEquals(0, server.closeRequests.get());

    Thread.sleep(80); // exceeds the 50ms reassembly timeout
    down.expire();

    assertEquals(1, server.closeRequests.get());
    assertTrue(down.isClosed());
  }

  /** Invalid UTF-8 in a completed packet closes once with no delivery. */
  @Test
  void invalidUtf8ClosesWithoutDelivery() {
    Recorder server = new Recorder();
    UUID publisher = UUID.randomUUID();
    NotificationPeerLink down = server.link(UUID.randomUUID(), limits(16000, 32));

    NotificationPacket malformed =
        new NotificationPacket(
            publisher, null, TOPIC, UUID.randomUUID(), new byte[] {(byte) 0xFF, (byte) 0xFE});
    down.accept(NotificationCarrier.chunk(malformed, 0).encode());

    assertTrue(server.messages.isEmpty());
    assertEquals(1, server.closeRequests.get());
    assertTrue(down.isClosed());
  }

  /**
   * A delivery callback may re-enter the link (a receiver replying with offer) without deadlock.
   */
  @Test
  void deliveryCallbackMayReenterWithOffer() {
    UUID serverId = UUID.randomUUID();
    AtomicReference<NotificationPeerLink> ref = new AtomicReference<>();
    NotificationPeerLink down =
        new NotificationPeerLink(
            serverId,
            TOPIC,
            limits(16000, 32),
            null,
            body -> ref.get().offer(UUID.randomUUID(), "reply:" + body),
            () -> {});
    ref.set(down);
    Recorder daemon = new Recorder();
    NotificationPeerLink up = daemon.link(UUID.randomUUID(), limits(16000, 32));

    assertTrue(up.offer(UUID.randomUUID(), "hello"));
    drain(up, down);

    assertTrue(down.pollBatch().isPresent(), "reply accepted from the delivery callback");
  }

  /** close() releases queued and in-flight reservations and cancels the periodic expiry task. */
  @Test
  void closeReleasesBudgetAndCancelsExpiry() {
    ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    timer.setRemoveOnCancelPolicy(true);
    try {
      NotificationPeerLink link =
          new NotificationPeerLink(
              UUID.randomUUID(), TOPIC, limits(16000, 1), timer, body -> {}, () -> {});
      assertTrue(link.offer(UUID.randomUUID(), "x".repeat(1000)));
      assertTrue(link.offer(UUID.randomUUID(), "y".repeat(1000)));
      assertTrue(link.pollBatch().isPresent()); // one packet becomes in-flight
      assertFalse(timer.getQueue().isEmpty());

      link.close();

      assertTrue(link.isClosed());
      assertEquals(Optional.empty(), link.pollBatch());
      assertFalse(link.offer(UUID.randomUUID(), "z"));
      assertTrue(timer.getQueue().isEmpty());
      assertEquals(0, link.pendingBytes());
    } finally {
      timer.shutdownNow();
    }
  }

  /** hasPending() 在排队/在飞时可用，并在最后一包释放或 close 后归零。 */
  @Test
  void hasPendingTracksQueuedInFlightAndClose() {
    Recorder recorder = new Recorder();
    NotificationPeerLink link = recorder.link(UUID.randomUUID(), limits(16000, 1));
    try {
      assertFalse(link.hasPending());
      assertTrue(link.offer(UUID.randomUUID(), "x".repeat(1000)));
      assertTrue(link.hasPending());

      // sendBatchFrames=1：在飞未 complete 前仍算 pending，逐片 release 后归零。
      Optional<NotificationOutbox.Batch> batch = link.pollBatch();
      assertTrue(batch.isPresent());
      assertTrue(link.hasPending());
      while (batch.isPresent()) {
        link.complete(batch.get(), true);
        batch = link.pollBatch();
      }
      assertFalse(link.hasPending());
    } finally {
      link.close();
    }
    assertFalse(link.hasPending());
  }
}
