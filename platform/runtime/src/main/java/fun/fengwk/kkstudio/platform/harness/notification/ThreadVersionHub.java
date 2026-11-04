package fun.fengwk.kkstudio.platform.harness.notification;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscription;
import fun.fengwk.kkstudio.harness.api.HarnessThreadVersionChanges;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.platform.notification.SubscriptionFanOut;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * PostgreSQL Thread version 通知的进程内 fan-out，同时提供公开订阅契约与内部纯 wake 源。
 *
 * <p>初始订阅 cursor 从权威 version 窄查询读取；notification payload 携带提交后的 {@code threadId:version}，合法 payload
 * 只做 轻量解析与 fan-out，畸形 payload 广播 resync。共享 LISTEN 循环建连/重连成功时也调用 {@link
 * #broadcastResync()}，覆盖断连期间不可恢复的通知。{@link #subscribe} 先注册 consumer 再读当前 version 返回，保证返回的 cursor
 * 之后的事件不因注册竞态丢失。
 */
@Slf4j
public final class ThreadVersionHub
    implements HarnessThreadVersionChanges, HarnessThreadChangeSource {

  private final Supplier<HarnessRuntime> runtime;
  private final SubscriptionFanOut<Event> subscribers = new SubscriptionFanOut<>();

  public ThreadVersionHub(Supplier<HarnessRuntime> runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public HarnessChangeSubscription subscribe(UUID threadId, Consumer<Event> consumer) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(consumer, "consumer");
    // 先注册 consumer 再读当前 version 返回，保证返回的 cursor 之后的事件不因注册竞态丢失。
    Runnable unsubscribe = subscribers.subscribe(threadId, consumer);
    try {
      return new HarnessChangeSubscription(currentVersion(threadId), unsubscribe);
    } catch (RuntimeException | Error error) {
      // 权威查询失败必须释放刚建立的注册，并保留原始异常（Error 同样不得吞）。
      unsubscribe.run();
      throw error;
    }
  }

  /** 内部观察者只关心「是否可能发生变化」：version 与 resync 事件都折叠为一次 wake。 */
  @Override
  public Subscription subscribe(UUID threadId, Runnable onChange) {
    Objects.requireNonNull(onChange, "onChange");
    return subscribe(threadId, ignored -> onChange.run())::close;
  }

  /** 由统一 PostgreSQL listener 交付一条 {@link HarnessNotificationChannel#THREAD_VERSION} 通知。 */
  public void onNotification(String payload) {
    ThreadVersion notification = parseNotification(payload);
    if (notification == null) {
      log.warn("malformed thread version notification payload={}; broadcasting resync", payload);
      broadcastResync();
      return;
    }
    subscribers.publish(notification.threadId(), new Event.Version(notification.version()));
  }

  /** 建连/重连：覆盖断连期间不可恢复的通知。 */
  public void broadcastResync() {
    subscribers.broadcast(new Event.Resync());
  }

  private long currentVersion(UUID threadId) {
    return runtime
        .get()
        .findThreadVersion(threadId)
        .orElseThrow(() -> new IllegalArgumentException("unknown thread: " + threadId));
  }

  static ThreadVersion parseNotification(String payload) {
    if (payload == null) {
      return null;
    }
    int colon = payload.indexOf(':');
    if (colon <= 0 || colon != payload.lastIndexOf(':')) {
      return null;
    }
    String rawThreadId = payload.substring(0, colon);
    String rawVersion = payload.substring(colon + 1);
    try {
      UUID threadId = Uuids.parseCanonical(rawThreadId, "threadId");
      long version = DecimalLongs.parseNonNegative(rawVersion, "version");
      return new ThreadVersion(threadId, version);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  record ThreadVersion(UUID threadId, long version) {}
}
