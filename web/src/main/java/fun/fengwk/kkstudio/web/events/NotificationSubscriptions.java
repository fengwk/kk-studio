package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.canvas.notification.CanvasNotifications;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.harness.infra.realtime.BusRealtimeEventSource;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.project.controller.IssueControllerDispatcher;
import fun.fengwk.kkstudio.project.notification.ProjectNotifications;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 唯一 NotificationBus 上的全部领域静态订阅绑定。
 *
 * <p>订阅只把总线已解码的 payload 转成 hub 定点失效或调度器唤醒；与单条 payload 无关的失效（建连/重连）走订阅的 resync，畸形 payload 由 topic
 * codec 拒绝并同样退化为 resync。绑定集合即组合根对「哪些 topic 有本地消费者」的完整声明，不提供动态注册或回退路径。
 *
 * <p>{@code harness.thread.tree} 与 {@code harness.tool.interaction} 的 payload
 * 允许为空实体：它表示来源事实已不存在，必须对整个资源全量 resync，而不是丢弃这条提示。
 */
final class NotificationSubscriptions implements AutoCloseable {

  private final List<NotificationSubscription> subscriptions;

  private NotificationSubscriptions(List<NotificationSubscription> subscriptions) {
    this.subscriptions = subscriptions;
  }

  /** 绑定全部领域订阅；任一绑定失败时逐一关闭已建立的订阅，不遗留半绑定状态。 */
  static NotificationSubscriptions bind(
      NotificationBus bus,
      HarnessWorkDispatcher harnessWorkDispatcher,
      IssueControllerDispatcher issueControllerDispatcher,
      CanvasFunctionDispatcher canvasFunctionDispatcher,
      ThreadVersionHub threadVersionHub,
      CanvasVersionHub canvasVersionHub,
      ProjectInvalidationHub projectInvalidationHub,
      SystemSettingsChangeHandler systemSettingsChangeHandler,
      BusRealtimeEventSource realtimeEventSource,
      EnvironmentSkillSyncOrchestrator environmentSkillSyncOrchestrator,
      ExecutionTreeChangeHub executionTreeChangeHub,
      InteractionChangeHub interactionChangeHub,
      EnvironmentChangeHub environmentChangeHub) {
    Objects.requireNonNull(bus, "bus");
    List<NotificationSubscription> subscriptions = new ArrayList<>();
    try {
      // 1. 任务调度唤醒：有新 Harness 任务就绪，唤醒调度器 drain 排空；并发抢占由 FOR UPDATE SKIP LOCKED 保证。
      subscriptions.add(
          bus.subscribe(
              HarnessNotifications.WORK_AVAILABLE,
              ignored -> harnessWorkDispatcher.wake(),
              harnessWorkDispatcher::wake));
      // 2. Thread 版本失效：payload 为实体 id 与真实 version，通知 WebSocket 广播版本事件触发快照对账。
      subscriptions.add(
          bus.subscribe(
              HarnessNotifications.THREAD_VERSION,
              threadVersionHub::onNotification,
              threadVersionHub::broadcastResync));
      // 3. 执行树失效：payload 为真实执行根 id；空实体表示来源事实已不存在，退化为全量 resync。
      subscriptions.add(
          bus.subscribe(
              HarnessNotifications.THREAD_TREE,
              hint -> executionTreeChangeHub.onNotification(hint.entityId()),
              executionTreeChangeHub::broadcastResync));
      // 4. 待处理交互失效：payload 为真实执行根 id；空实体表示来源事实已不存在，退化为全量 resync。
      subscriptions.add(
          bus.subscribe(
              HarnessNotifications.TOOL_INTERACTION,
              hint -> interactionChangeHub.onNotification(hint.entityId()),
              interactionChangeHub::broadcastResync));
      // 5. 流式增量推送：大模型文本 Delta 与工具局部输出直接推送前端，不落库。
      subscriptions.add(
          bus.subscribe(
              HarnessNotifications.REALTIME,
              realtimeEventSource::onEnvelope,
              realtimeEventSource::onResync));
      // 6. 画布 revision 失效：payload 为实体 id 与真实 revision。
      subscriptions.add(
          bus.subscribe(
              CanvasNotifications.REVISION,
              canvasVersionHub::onNotification,
              canvasVersionHub::broadcastResync));
      // 7. 画布函数唤醒：有新 Canvas 异步函数计算任务就绪。
      subscriptions.add(
          bus.subscribe(
              CanvasNotifications.FUNCTION_WORK,
              ignored -> canvasFunctionDispatcher.wake(),
              canvasFunctionDispatcher::wake));
      // 8. Project/Issue 失效：payload 为 project id，提交后提示浏览器回读权威快照。
      subscriptions.add(
          bus.subscribe(
              ProjectNotifications.ISSUE_CHANGED,
              projectInvalidationHub::onNotification,
              projectInvalidationHub::broadcastResync));
      // 9. Issue Work 到期唤醒：payload 为 issue id，唤醒 Issue Controller 调度器。
      subscriptions.add(
          bus.subscribe(
              ProjectNotifications.WORK_DUE,
              ignored -> issueControllerDispatcher.wake(),
              issueControllerDispatcher::wake));
      // 10. 系统设置同步：任一节点修改全局设置提交后，广播通知所有节点原子回读最新快照。
      subscriptions.add(
          bus.subscribe(
              PlatformNotifications.SETTINGS_CHANGED,
              systemSettingsChangeHandler::onNotification,
              systemSettingsChangeHandler::onResync));
      // 11. Skill Package 变更：把该 Package 同步到本节点全部 READY 的 Environment；建连/重连时全量对账。
      subscriptions.add(
          bus.subscribe(
              PlatformNotifications.SKILL_PACKAGE_CHANGED,
              environmentSkillSyncOrchestrator::onPackageChanged,
              environmentSkillSyncOrchestrator::reconcileReadyEnvironments));
      // 12. 环境连接变更：payload 为环境 id，环境精准失效，交互同步全量对账。
      subscriptions.add(
          bus.subscribe(
              PlatformNotifications.ENVIRONMENT_CHANGED,
              environmentId -> {
                environmentChangeHub.onNotification(environmentId);
                interactionChangeHub.broadcastResync();
              },
              () -> {
                environmentChangeHub.broadcastResync();
                interactionChangeHub.broadcastResync();
              }));
    } catch (RuntimeException | Error error) {
      // 清理必须尝试关闭每一个已建立的订阅：首个关闭失败绝不跳过其余资源，只作为 suppressed 附加到绑定的原始异常。
      for (NotificationSubscription subscription : subscriptions) {
        try {
          subscription.close();
        } catch (RuntimeException closeError) {
          if (closeError != error) {
            error.addSuppressed(closeError);
          }
        }
      }
      throw error;
    }
    return new NotificationSubscriptions(subscriptions);
  }

  /** 关闭全部订阅并上报第一个失败，在途的全部关闭都已被尝试；关闭失败只会让总线判定为不健康，因此不做重试、兜底或吞异常。 */
  @Override
  public void close() {
    RuntimeException failure = null;
    for (NotificationSubscription subscription : subscriptions) {
      try {
        subscription.close();
      } catch (RuntimeException error) {
        if (failure == null) {
          failure = error;
        } else if (failure != error) {
          failure.addSuppressed(error);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
