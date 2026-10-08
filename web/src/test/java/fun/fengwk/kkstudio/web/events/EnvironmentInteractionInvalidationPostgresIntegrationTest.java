package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.infra.notification.HarnessNotifications;
import fun.fengwk.kkstudio.notification.DefaultNotificationBus;
import fun.fengwk.kkstudio.platform.notification.PlatformNotifications;
import fun.fengwk.kkstudio.share.notification.EntityHint;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
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
  @Autowired private DefaultNotificationBus bus;
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
      bus.publish(
          PlatformNotifications.ENVIRONMENT_CHANGED,
          NotificationAddress.broadcast(),
          environmentId);
      awaitUntil(
          () ->
              countResyncs(interactionSignals) > initialInteractionResyncs
                  && environmentSignals.stream()
                      .anyMatch(signal -> signal instanceof Signal.EnvironmentChanged),
          "environment change notification delivers EnvironmentChanged and triggers interaction Resync");

      // 后续交互通知投递执行根
      bus.publish(
          HarnessNotifications.TOOL_INTERACTION,
          NotificationAddress.broadcast(),
          new EntityHint(rootThreadId));
      awaitUntil(
          () ->
              interactionSignals.stream()
                  .anyMatch(
                      signal ->
                          signal instanceof Signal.InteractionsChanged changed
                              && rootThreadId.equals(changed.rootThreadId())),
          "interaction notification delivers InteractionsChanged with rootThreadId");
      assertFalse(
          interactionSignals.stream()
              .anyMatch(
                  signal ->
                      signal instanceof Signal.InteractionsChanged changed
                          && !rootThreadId.equals(changed.rootThreadId())),
          "an environment id must never be delivered as an interaction root");
      assertTrue(countResyncs(interactionSignals) > initialInteractionResyncs);

      long interactionResyncsBefore = countResyncs(interactionSignals);
      // EntityHint(null) 空实体表示来源事实已不存在，必须退化为交互全量 resync
      bus.publish(
          HarnessNotifications.TOOL_INTERACTION,
          NotificationAddress.broadcast(),
          new EntityHint(null));
      awaitUntil(
          () -> countResyncs(interactionSignals) > interactionResyncsBefore,
          "null entity hint must trigger interaction resync");
    } finally {
      interactionSubscription.close();
    }
  }

  private static void awaitUntil(BooleanSupplier observed, String message) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (observed.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while awaiting: " + message, interrupted);
      }
    }
    throw new AssertionError("condition was never satisfied within timeout: " + message);
  }

  private static long countResyncs(List<Signal> signals) {
    return signals.stream().filter(signal -> signal instanceof Signal.Resync).count();
  }
}
