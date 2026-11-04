package fun.fengwk.kkstudio.platform.harness.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.HarnessInvalidationChanges.Event;
import fun.fengwk.kkstudio.harness.api.HarnessThreadVersionChanges;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HarnessChangeNotifications：三个 harness channel 的协议只在此定义一次，且各自的通知只投递给自己的订阅源。
 *
 * <p>公开契约的消费语义（version cursor、畸形 payload resync）由各源自己的测试覆盖；本测试固定绑定对应的 channel 与投递方向， 防止组合根重新复制
 * channel 字符串或串线。
 */
class HarnessChangeNotificationsTest {

  private static final UUID THREAD = new UUID(0L, 7L);

  private ThreadVersionHub threadVersionHub;
  private HarnessChangeNotifications notifications;

  @BeforeEach
  void setUp() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    when(runtime.findThreadVersion(THREAD)).thenReturn(Optional.of(3L));
    threadVersionHub = new ThreadVersionHub(() -> runtime);
    notifications = new HarnessChangeNotifications(threadVersionHub);
  }

  @Test
  void bindingsUseTheSingleChannelProtocol() {
    assertEquals(
        HarnessNotificationChannel.THREAD_VERSION,
        notifications.threadVersionsNotification().channel());
    assertEquals(
        HarnessNotificationChannel.THREAD_TREE,
        notifications.executionTreeNotification().channel());
    assertEquals(
        HarnessNotificationChannel.TOOL_INTERACTION,
        notifications.interactionsNotification().channel());
  }

  @Test
  void publicSourcesExposeTheSharedThreadVersionHub() {
    assertSame(threadVersionHub, notifications.threadVersions());
  }

  @Test
  void everyNotificationOnlyReachesItsOwnSource() {
    List<HarnessThreadVersionChanges.Event> versions = new ArrayList<>();
    List<Event> trees = new ArrayList<>();
    List<Event> interactions = new ArrayList<>();
    notifications.threadVersions().subscribe(THREAD, versions::add);
    notifications.executionTree().subscribe(null, trees::add);
    notifications.interactions().subscribe(null, interactions::add);

    notifications.threadVersionsNotification().onNotification().accept(THREAD + ":4");
    notifications.executionTreeNotification().onNotification().accept(THREAD.toString());
    notifications.interactionsNotification().onNotification().accept(THREAD.toString());

    assertEquals(List.of(new HarnessThreadVersionChanges.Event.Version(4L)), versions);
    assertEquals(List.of(new Event.Changed(THREAD)), trees);
    assertEquals(List.of(new Event.Changed(THREAD)), interactions);
  }

  @Test
  void resyncOfOneSourceDoesNotTouchTheOthers() {
    List<HarnessThreadVersionChanges.Event> versions = new ArrayList<>();
    List<Event> interactions = new ArrayList<>();
    notifications.threadVersions().subscribe(THREAD, versions::add);
    notifications.interactions().subscribe(null, interactions::add);

    // 环境失效等外部提示只要求交互全量回读：组合根调用该绑定的 resync，不影响版本源。
    notifications.interactionsNotification().onResync().run();

    assertEquals(List.of(new Event.Resync()), interactions);
    assertEquals(List.of(), versions);
  }
}
