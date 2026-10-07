package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

/** 真实 PostgreSQL 通知回归：环境 id 只能驱动环境 changed 与交互 resync，执行根才驱动交互 changed。 */
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

      UUID environmentId = UUID.randomUUID();
      UUID rootThreadId = UUID.randomUUID();
      assertNotEquals(rootThreadId, environmentId);
      jdbc.update(
          "insert into environment (id, name, registration_token) values (?, ?, ?)",
          environmentId,
          "notification-routing",
          "test-token-" + environmentId);
      long initialInteractionResyncs = countResyncs(interactionSignals);
      notifyUntil(
          () ->
              countResyncs(interactionSignals) > initialInteractionResyncs
                  && environmentSignals.stream()
                      .anyMatch(signal -> signal instanceof Signal.EnvironmentChanged),
          EnvironmentChangeHub.CHANNEL,
          environmentId.toString());

      // 同一 LISTEN 连接的后续根通知也是投递围栏，避免只在环境回调的中途检查负断言。
      notifyUntil(
          () ->
              interactionSignals.stream()
                  .anyMatch(
                      signal ->
                          signal instanceof Signal.InteractionsChanged changed
                              && rootThreadId.equals(changed.rootThreadId())),
          InteractionChangeHub.CHANNEL,
          rootThreadId.toString());
      assertFalse(
          interactionSignals.stream()
              .anyMatch(
                  signal ->
                      signal instanceof Signal.InteractionsChanged changed
                          && !rootThreadId.equals(changed.rootThreadId())),
          "an environment id must never be delivered as an interaction root");
      assertTrue(countResyncs(interactionSignals) > initialInteractionResyncs);

      long interactionResyncsBefore = countResyncs(interactionSignals);
      long environmentResyncsBefore = countResyncs(environmentSignals);
      notifyUntil(
          () ->
              countResyncs(interactionSignals) > interactionResyncsBefore
                  && countResyncs(environmentSignals) > environmentResyncsBefore,
          EnvironmentChangeHub.CHANNEL,
          "");
    } finally {
      interactionSubscription.close();
    }
  }

  /** 在 LISTEN 建立前投递的 NOTIFY 会丢失，因此这里按固定节奏重投直到观察到信号；断言只要求「曾经送达」，与投递次数无关。 */
  private void notifyUntil(BooleanSupplier observed, String channel, String payload) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      jdbc.queryForObject("select pg_notify(?, ?)", String.class, channel, payload);
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
        channel + " notification with payload '" + payload + "' was never delivered");
  }

  private static long countResyncs(List<Signal> signals) {
    return signals.stream().filter(signal -> signal instanceof Signal.Resync).count();
  }
}
