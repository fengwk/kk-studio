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

  <T> NotificationSubscription subscribe(
      NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync);

  @Override
  void close();
}
