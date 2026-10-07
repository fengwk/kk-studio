package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.web.WebPostgresTestSupport;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKey;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.ResourceKind;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Signal;
import fun.fengwk.kkstudio.web.events.ApplicationEventHub.Subscription;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 环境连接变更必须同时失效「环境读模型」与「待处理交互读模型」的真实 PostgreSQL 通知回归。
 *
 * <p>测试意图：环境上线/离线会改变「待领取环境等待」事实，而该事实不由任何交互/Work 写事件产生；只有真实 {@code environment_changed} 通知同时驱动两个
 * Hub，浏览器才能在不刷新页面时看到环境等待条目的出现与消失。空 payload（来源事实已不存在）与 LISTEN 重连一样必须退化为两个资源各自的全量 resync。
 */
class EnvironmentInteractionInvalidationPostgresIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ApplicationEventHub hub;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void environmentChangedNotificationInvalidatesEnvironmentAndInteractionReadModels() {
    List<Signal> interactionSignals = new CopyOnWriteArrayList<>();
    List<Signal> environmentSignals = new CopyOnWriteArrayList<>();
    Subscription interactionSubscription =
        hub.subscribe(new ResourceKey(ResourceKind.INTERACTIONS, null), interactionSignals::add);
    interactionSubscription.activate();
    try (Subscription environmentSubscription =
        hub.subscribe(new ResourceKey(ResourceKind.ENVIRONMENTS, null), environmentSignals::add)) {
      environmentSubscription.activate();

      UUID rootThreadId = UUID.randomUUID();
      notifyUntil(
          () ->
              interactionSignals.stream()
                  .anyMatch(
                      signal ->
                          signal instanceof Signal.InteractionsChanged changed
                              && rootThreadId.equals(changed.rootThreadId())),
          rootThreadId.toString());
      assertTrue(
          environmentSignals.stream()
              .anyMatch(signal -> signal instanceof Signal.EnvironmentChanged),
          "an environment connection change must invalidate the environment read model");

      long interactionResyncsBefore = countResyncs(interactionSignals);
      long environmentResyncsBefore = countResyncs(environmentSignals);
      notifyUntil(
          () ->
              countResyncs(interactionSignals) > interactionResyncsBefore
                  && countResyncs(environmentSignals) > environmentResyncsBefore,
          "");
    } finally {
      interactionSubscription.close();
    }
  }

  /** 在 LISTEN 建立前投递的 NOTIFY 会丢失，因此这里按固定节奏重投直到观察到信号；断言只要求「曾经送达」，与投递次数无关。 */
  private void notifyUntil(BooleanSupplier observed, String payload) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      jdbc.execute("select pg_notify('environment_changed', '" + payload + "')");
      if (observed.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while awaiting notification delivery");
      }
    }
    throw new AssertionError(
        "environment_changed notification with payload '" + payload + "' was never delivered");
  }

  private static long countResyncs(List<Signal> signals) {
    return signals.stream().filter(signal -> signal instanceof Signal.Resync).count();
  }
}
