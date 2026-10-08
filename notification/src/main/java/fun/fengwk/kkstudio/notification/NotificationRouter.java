package fun.fengwk.kkstudio.notification;

import fun.fengwk.kkstudio.share.notification.Notification;

import java.util.UUID;

/** Topology selects recipients, never a second consumer path. */
final class NotificationRouter {
  private final UUID self;
  private final LocalInbox inbox;
  private final CrossNodeTransport transport;

  NotificationRouter(UUID self, LocalInbox inbox, CrossNodeTransport transport) {
    this.self = self;
    this.inbox = inbox;
    this.transport = transport;
  }

  void remote(WireMessage message, boolean transactional) {
    if (!self.equals(message.target())) {
      transport.send(message, transactional);
    }
  }

  void local(Notification<?> message, int bytes) {
    if (message.address().nodeId() == null || self.equals(message.address().nodeId())) {
      inbox.accept(message, bytes);
    }
  }
}
