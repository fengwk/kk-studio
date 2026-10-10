package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationOutbox;
import fun.fengwk.kkstudio.share.notification.NotificationPeerLink;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test-side peer endpoint built on the production {@link NotificationPeerLink}. It never defines a
 * second encoder: outbound logical bodies go through the same carrier/outbox, and inbound physical
 * frames are reassembled by the same link, so a test socket behaves like a real endpoint.
 */
final class TestPeer {

  private final UUID self;
  private final NotificationPeerLink link;
  private final List<String> inbox = new ArrayList<>();

  TestPeer(UUID self, String topic, NotificationLimits limits) {
    this.self = self;
    this.link = new NotificationPeerLink(self, topic, limits, null, inbox::add, () -> {});
  }

  TestPeer(UUID self) {
    this(self, NotificationPeerLink.DAEMON_TOPIC, NotificationLimits.defaults());
  }

  UUID self() {
    return self;
  }

  /** Offers one complete logical body and returns every encoded fragment in batch order. */
  List<String> fragments(String body) {
    assertTrue(link.offer(UUID.randomUUID(), body));
    List<String> frames = new ArrayList<>();
    Optional<NotificationOutbox.Batch> batch;
    while ((batch = link.pollBatch()).isPresent()) {
      frames.addAll(batch.get().frames());
      link.complete(batch.get(), true);
    }
    return frames;
  }

  /**
   * Frames one logical body into a single physical fragment; only valid for bodies under one chunk.
   */
  String frame(String body) {
    return fragments(body).get(0);
  }

  void accept(String rawFrame) {
    link.accept(rawFrame);
  }

  List<String> inbox() {
    return List.copyOf(inbox);
  }
}
