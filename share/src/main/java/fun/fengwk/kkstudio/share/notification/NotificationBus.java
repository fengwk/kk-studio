package fun.fengwk.kkstudio.share.notification;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One publication entry point, with subscriber callbacks always executed off the publisher thread.
 */
public interface NotificationBus extends AutoCloseable {
  UUID nodeId();

  <T> void publish(NotificationTopic<T> topic, NotificationAddress address, T payload);

  <T> void publishBatch(NotificationTopic<T> topic, NotificationAddress address, List<T> payloads);

  /**
   * Register a subscriber; {@code consumer} and {@code resync} always run off the caller thread.
   *
   * <p>Establishing a subscription is itself an authoritative reconciliation boundary: an
   * asynchronous recovery marker is enqueued as the first mailbox work item, so a subscriber never
   * depends on transport connection timing to observe a change committed before it subscribed. The
   * marker is not executed inline on the subscribing thread, and it runs before any payload
   * delivered afterwards.
   */
  <T> NotificationSubscription subscribe(
      NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync);

  @Override
  void close();
}
