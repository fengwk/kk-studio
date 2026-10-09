package fun.fengwk.kkstudio.notification;

import fun.fengwk.kkstudio.share.notification.NotificationPacket;

/** Sending in a bound physical transaction must use that transaction and propagate SQL failure. */
interface CrossNodeTransport extends AutoCloseable {
  void send(NotificationPacket message, boolean transactional);

  void start();

  boolean healthy();

  @Override
  void close();
}
