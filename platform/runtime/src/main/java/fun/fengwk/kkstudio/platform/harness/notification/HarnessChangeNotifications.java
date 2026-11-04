package fun.fengwk.kkstudio.platform.harness.notification;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscription;
import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscriptions;
import fun.fengwk.kkstudio.harness.api.HarnessInvalidationChanges;
import fun.fengwk.kkstudio.harness.api.HarnessThreadVersionChanges;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.spi.HarnessThreadChangeSource;
import fun.fengwk.kkstudio.platform.notification.VersionNotificationHub;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Harness 变更订阅的公开入口实现：集中 Thread version、执行树与待处理交互三个 LISTEN channel 的协议与进程内分发。
 *
 * <p>Thread version 由 {@link VersionNotificationHub} 承担注册与分发的通用语义；本类只定义 channel、payload 解析与权威
 * version 读取。执行树与待处理交互共用 {@link HarnessInvalidationSource}。本类不创建连接或线程；统一 LISTEN 循环由组合根持有，只按三个
 * {@link HarnessNotificationBinding} 投递 payload 与重连 resync。
 */
public final class HarnessChangeNotifications
    implements HarnessChangeSubscriptions, HarnessThreadVersionChanges, HarnessThreadChangeSource {

  private final Supplier<HarnessRuntime> runtime;
  private final VersionNotificationHub<HarnessThreadVersionChanges.Event> threadVersions =
      new VersionNotificationHub<>(
          HarnessChangeNotifications::parseThreadVersion,
          HarnessThreadVersionChanges.Event.Version::new,
          HarnessThreadVersionChanges.Event.Resync::new);
  private final HarnessInvalidationSource executionTree = new HarnessInvalidationSource();
  private final HarnessInvalidationSource interactions = new HarnessInvalidationSource();

  public HarnessChangeNotifications(Supplier<HarnessRuntime> runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public HarnessThreadVersionChanges threadVersions() {
    return this;
  }

  @Override
  public HarnessInvalidationChanges executionTree() {
    return executionTree;
  }

  @Override
  public HarnessInvalidationChanges interactions() {
    return interactions;
  }

  @Override
  public HarnessChangeSubscription subscribe(UUID threadId, Consumer<Event> consumer) {
    VersionNotificationHub.Subscription subscribed =
        threadVersions.subscribe(threadId, this::currentVersion, consumer);
    return new HarnessChangeSubscription(subscribed.cursor(), subscribed.release());
  }

  /** 内部观察者只关心「是否可能发生变化」：version 与 resync 事件都折叠为一次 wake。 */
  @Override
  public Subscription subscribe(UUID threadId, Runnable onChange) {
    Objects.requireNonNull(onChange, "onChange");
    return subscribe(threadId, ignored -> onChange.run())::close;
  }

  /** Thread version 失效通知绑定。 */
  public HarnessNotificationBinding threadVersionsNotification() {
    return new HarnessNotificationBinding(
        HarnessNotificationChannel.THREAD_VERSION,
        threadVersions::onNotification,
        threadVersions::broadcastResync);
  }

  /** 执行树失效通知绑定。 */
  public HarnessNotificationBinding executionTreeNotification() {
    return new HarnessNotificationBinding(
        HarnessNotificationChannel.THREAD_TREE,
        executionTree::onNotification,
        executionTree::broadcastResync);
  }

  /** 待处理交互失效通知绑定。 */
  public HarnessNotificationBinding interactionsNotification() {
    return new HarnessNotificationBinding(
        HarnessNotificationChannel.TOOL_INTERACTION,
        interactions::onNotification,
        interactions::broadcastResync);
  }

  private long currentVersion(UUID threadId) {
    return runtime
        .get()
        .findThreadVersion(threadId)
        .orElseThrow(() -> new IllegalArgumentException("unknown thread: " + threadId));
  }

  /** Thread version payload 为 {@code {threadId}:{version}}；畸形 payload 返回 {@code null} 触发 resync。 */
  static VersionNotificationHub.Notification parseThreadVersion(String payload) {
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
      return new VersionNotificationHub.Notification(threadId, version);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
