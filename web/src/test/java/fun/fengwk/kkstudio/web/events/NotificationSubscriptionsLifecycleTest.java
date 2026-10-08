package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.infra.function.CanvasFunctionDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.realtime.BusRealtimeEventSource;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSyncOrchestrator;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsChangeHandler;
import fun.fengwk.kkstudio.project.controller.IssueControllerDispatcher;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;
import fun.fengwk.kkstudio.share.notification.NotificationTopic;
import fun.fengwk.kkstudio.web.project.ProjectInvalidationHub;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 订阅绑定的资源生命周期：绑定失败与关闭都必须尝试释放每一个已建立的订阅，绝不因首个失败跳过其余资源。
 *
 * <p>领域协作者用 mock 占位：本用例只验证绑定/关闭的资源路径，订阅回调不会被调用。
 */
class NotificationSubscriptionsLifecycleTest {

  /** 绑定失败必须关闭全部已建立的订阅，原样抛出绑定异常，并把关闭失败作为 suppressed 附加上报。 */
  @Test
  void bindFailureClosesEveryCreatedSubscriptionAndRethrowsOriginal() {
    StubBus bus = new StubBus(3, true);

    IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> bind(bus));

    assertSame(bus.failure, thrown, "必须原样抛出绑定异常，不得包装");
    assertEquals(2, bus.created.size());
    assertTrue(
        bus.created.stream().allMatch(subscription -> subscription.closed), "绑定失败后必须关闭每一个已建立的订阅");
    assertEquals(1, thrown.getSuppressed().length, "关闭失败必须作为 suppressed 附加上报");
    assertSame(bus.closeFailure, thrown.getSuppressed()[0]);
  }

  /** 关闭必须尝试关闭所有订阅：首个失败不跳过其余资源，并在最后上报第一个异常。 */
  @Test
  void closeAttemptsEverySubscriptionAndReportsFirstFailure() {
    StubBus bus = new StubBus(0, false);
    NotificationSubscriptions subscriptions = bind(bus);
    assertEquals(12, bus.created.size());
    RuntimeException second = new RuntimeException("close 2 failed");
    RuntimeException fourth = new RuntimeException("close 4 failed");
    bus.created.get(1).closeFailure = second;
    bus.created.get(3).closeFailure = fourth;

    RuntimeException thrown = assertThrows(RuntimeException.class, subscriptions::close);

    assertSame(second, thrown, "必须上报第一个关闭失败");
    assertSame(fourth, thrown.getSuppressed()[0], "后续关闭失败必须作为 suppressed 附加上报");
    assertTrue(
        bus.created.stream().allMatch(subscription -> subscription.closed), "首个关闭失败后仍必须尝试关闭其余订阅");
  }

  private static NotificationSubscriptions bind(NotificationBus bus) {
    return NotificationSubscriptions.bind(
        bus,
        mock(HarnessWorkDispatcher.class),
        mock(IssueControllerDispatcher.class),
        mock(CanvasFunctionDispatcher.class),
        mock(ThreadVersionHub.class),
        mock(CanvasVersionHub.class),
        mock(ProjectInvalidationHub.class),
        mock(SystemSettingsChangeHandler.class),
        mock(BusRealtimeEventSource.class),
        mock(EnvironmentSkillSyncOrchestrator.class),
        mock(ExecutionTreeChangeHub.class),
        mock(InteractionChangeHub.class),
        mock(EnvironmentChangeHub.class));
  }

  /** 最小 NotificationBus stub：让第 {@code failAt} 次订阅抛出异常，并记录每个建立/关闭的订阅。 */
  private static final class StubBus implements NotificationBus {

    private final int failAt;
    private final boolean failFirstClose;
    final List<StubSubscription> created = new ArrayList<>();
    final IllegalStateException failure = new IllegalStateException("bind rejected");
    final RuntimeException closeFailure = new RuntimeException("close 1 failed");
    private int subscribes;

    StubBus(int failAt, boolean failFirstClose) {
      this.failAt = failAt;
      this.failFirstClose = failFirstClose;
    }

    @Override
    public UUID nodeId() {
      return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    @Override
    public <T> void publish(NotificationTopic<T> topic, NotificationAddress address, T payload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> void publishBatch(
        NotificationTopic<T> topic, NotificationAddress address, List<T> payloads) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> NotificationSubscription subscribe(
        NotificationTopic<T> topic, Consumer<T> consumer, Runnable resync) {
      subscribes++;
      if (subscribes == failAt) {
        throw failure;
      }
      StubSubscription subscription = new StubSubscription();
      if (failFirstClose && created.isEmpty()) {
        // 首个订阅关闭失败：清理仍必须继续关闭其余订阅，并把该失败作为 suppressed 上报。
        subscription.closeFailure = closeFailure;
      }
      created.add(subscription);
      return subscription;
    }

    @Override
    public void close() {}
  }

  private static final class StubSubscription implements NotificationSubscription {

    volatile boolean closed;
    volatile RuntimeException closeFailure;

    @Override
    public State state() {
      return closed ? State.CLOSED : State.ACTIVE;
    }

    @Override
    public void close() {
      closed = true;
      if (closeFailure != null) {
        throw closeFailure;
      }
    }
  }
}
