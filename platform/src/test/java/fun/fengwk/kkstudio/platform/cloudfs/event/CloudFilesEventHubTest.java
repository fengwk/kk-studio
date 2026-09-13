package fun.fengwk.kkstudio.platform.cloudfs.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** {@link CloudFilesEventHub} 单元测试。 */
class CloudFilesEventHubTest {

  @Test
  void notificationAndResyncFanOutUntilSubscriptionCloses() throws Exception {
    // 意图：数据库通知与重连对账都触发失效信号，关闭订阅后不再接收。
    CloudFilesEventHub hub = new CloudFilesEventHub();
    AtomicInteger count = new AtomicInteger();
    AutoCloseable subscription = hub.subscribe(count::incrementAndGet);

    hub.onNotification("00000000-0000-0000-0000-000000000001");
    hub.onNotification(null);
    hub.broadcastResync();
    assertEquals(3, count.get());

    subscription.close();
    hub.onNotification("ignored");
    assertEquals(3, count.get());
  }

  @Test
  void subscriberExceptionIsIsolated() {
    // 意图：单个订阅者抛异常不阻断其他订阅者接收失效信号。
    CloudFilesEventHub hub = new CloudFilesEventHub();
    AtomicInteger count = new AtomicInteger();
    hub.subscribe(
        () -> {
          throw new RuntimeException("boom");
        });
    hub.subscribe(count::incrementAndGet);

    hub.broadcastResync();

    assertEquals(1, count.get());
  }
}
