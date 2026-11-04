package fun.fengwk.kkstudio.platform.harness.notification;

import fun.fengwk.kkstudio.harness.api.HarnessChangeSubscriptions;
import fun.fengwk.kkstudio.harness.api.HarnessInvalidationChanges;
import fun.fengwk.kkstudio.harness.api.HarnessThreadVersionChanges;

import java.util.Objects;

/**
 * Harness 变更订阅的公开入口实现：集中 Thread version、执行树与待处理交互三个 LISTEN channel 的协议与进程内分发。
 *
 * <p>本类不创建连接或线程；统一 LISTEN 循环由组合根持有，只按三个 {@link HarnessNotificationBinding} 投递 payload 与重连
 * resync。消费方使用 {@link HarnessChangeSubscriptions}，组合根使用本类取绑定。
 */
public final class HarnessChangeNotifications implements HarnessChangeSubscriptions {

  private final ThreadVersionHub threadVersions;
  private final HarnessInvalidationSource executionTree = new HarnessInvalidationSource();
  private final HarnessInvalidationSource interactions = new HarnessInvalidationSource();

  public HarnessChangeNotifications(ThreadVersionHub threadVersions) {
    this.threadVersions = Objects.requireNonNull(threadVersions, "threadVersions");
  }

  @Override
  public HarnessThreadVersionChanges threadVersions() {
    return threadVersions;
  }

  @Override
  public HarnessInvalidationChanges executionTree() {
    return executionTree;
  }

  @Override
  public HarnessInvalidationChanges interactions() {
    return interactions;
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
}
