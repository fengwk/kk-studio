package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 测试用通知总线：同步在调用线程把发布投递给本地匹配订阅，并记录全部发布供断言。
 *
 * <p>只实现生产 {@link NotificationBus} 的本地路径语义（node 匹配即投递、订阅回调在发布线程执行），不模拟 PG/跨节点；因此可断言 ShellGateway
 * 是否真的走 Bus、是否只对正确节点发布，以及本地目标不产生额外传输。
 */
final class RecordingNotificationBus implements NotificationBus {

  record Publication(String topic, NotificationAddress address, Object payload) {}

  private final UUID nodeId;
  private final List<Entry<?>> entries = new CopyOnWriteArrayList<>();
  private final List<Publication> publications = new CopyOnWriteArrayList<>();

  RecordingNotificationBus(UUID nodeId) {
    this.nodeId = nodeId;
  }

  @Override
  public UUID nodeId() {
    return nodeId;
  }

  @Override
  public <T> void publish(NotificationTopic<T> topic, NotificationAddress address, T payload) {
    publications.add(new Publication(topic.name(), address, payload));
    if (address.nodeId() != null && !nodeId.equals(address.nodeId())) {
      return;
    }
    for (Entry<?> entry : entries) {
      if (entry.topic == topic) {
        entry.dispatch(payload);
      }
    }
  }

  @Override
  public <T> void publishBatch(
      NotificationTopic<T> topic, NotificationAddress address, List<T> payloads) {
    for (T payload : payloads) {
      publish(topic, address, payload);
    }
  }

  @Override
  public <T> NotificationSubscription subscribe(
      NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync) {
    Entry<T> entry = new Entry<>(topic, consumer, resync);
    entries.add(entry);
    return new NotificationSubscription() {
      @Override
      public State state() {
        return State.ACTIVE;
      }

      @Override
      public void close() {
        entries.remove(entry);
      }
    };
  }

  @Override
  public void close() {}

  /** 主动触发某个 topic 的 resync 回调，模拟传输重连。 */
  void resync(NotificationTopic<?> topic) {
    for (Entry<?> entry : entries) {
      if (entry.topic == topic) {
        entry.resync.run();
      }
    }
  }

  List<Publication> publications() {
    return new ArrayList<>(publications);
  }

  List<Publication> publications(String topicName) {
    List<Publication> result = new ArrayList<>();
    for (Publication publication : publications) {
      if (publication.topic().equals(topicName)) {
        result.add(publication);
      }
    }
    return result;
  }

  private static final class Entry<T> {
    private final NotificationTopic<T> topic;
    private final Consumer<T> consumer;
    private final Runnable resync;

    private Entry(NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync) {
      this.topic = topic;
      this.consumer = consumer;
      this.resync = resync;
    }

    private void dispatch(Object payload) {
      consumer.accept(cast(payload));
    }

    @SuppressWarnings("unchecked")
    private T cast(Object payload) {
      return (T) payload;
    }
  }
}
