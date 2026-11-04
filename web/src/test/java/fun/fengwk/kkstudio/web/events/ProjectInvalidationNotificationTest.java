package fun.fengwk.kkstudio.web.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.notification.NotificationInvalidationHub;
import fun.fengwk.kkstudio.web.events.InvalidationEventSource.Event;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class ProjectInvalidationNotificationTest {

  @Test
  void dispatchesCanonicalProjectIdsAndReleasesSubscription() throws Exception {
    NotificationInvalidationHub<Event> hub =
        new ApplicationEventConfiguration().projectInvalidationHub();
    List<UUID> changed = new ArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    Runnable subscription = hub.subscribe(null, event -> record(event, changed, resyncs));
    UUID projectId = UUID.randomUUID();

    hub.onNotification(projectId.toString());
    assertEquals(List.of(projectId), changed);
    assertEquals(0, resyncs.get());

    subscription.run();
    hub.onNotification(projectId.toString());
    assertEquals(List.of(projectId), changed);
  }

  @Test
  void invalidPayloadAndReconnectTriggerResync() {
    NotificationInvalidationHub<Event> hub =
        new ApplicationEventConfiguration().projectInvalidationHub();
    AtomicInteger resyncs = new AtomicInteger();
    hub.subscribe(null, event -> record(event, new ArrayList<>(), resyncs));

    hub.onNotification(null);
    hub.onNotification("not-a-project-id");
    hub.onNotification(UUID.randomUUID().toString().toUpperCase());
    hub.broadcastResync();

    assertEquals(4, resyncs.get());
  }

  @Test
  void isolatesFailingSubscribers() {
    NotificationInvalidationHub<Event> hub =
        new ApplicationEventConfiguration().projectInvalidationHub();
    hub.subscribe(
        null,
        ignored -> {
          throw new IllegalStateException("subscriber failure");
        });
    List<UUID> changed = new ArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    hub.subscribe(null, event -> record(event, changed, resyncs));
    UUID projectId = UUID.randomUUID();

    hub.onNotification(projectId.toString());
    hub.broadcastResync();

    assertEquals(List.of(projectId), changed);
    assertEquals(1, resyncs.get());
  }

  private static void record(Event event, List<UUID> changed, AtomicInteger resyncs) {
    if (event.resync()) {
      resyncs.incrementAndGet();
    } else {
      changed.add(event.key());
    }
  }
}
