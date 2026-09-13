package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class ProjectInvalidationHubTest {

  @Test
  void dispatchesCanonicalProjectIdsAndReleasesSubscription() throws Exception {
    ProjectInvalidationHub hub = new ProjectInvalidationHub();
    List<UUID> changed = new ArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    AutoCloseable subscription = hub.subscribe(changed::add, resyncs::incrementAndGet);
    UUID projectId = UUID.randomUUID();

    hub.onNotification(projectId.toString());
    assertEquals(List.of(projectId), changed);
    assertEquals(0, resyncs.get());

    subscription.close();
    hub.onNotification(projectId.toString());
    assertEquals(List.of(projectId), changed);
  }

  @Test
  void invalidPayloadAndReconnectTriggerResync() {
    ProjectInvalidationHub hub = new ProjectInvalidationHub();
    AtomicInteger resyncs = new AtomicInteger();
    hub.subscribe(ignored -> {}, resyncs::incrementAndGet);

    hub.onNotification(null);
    hub.onNotification("not-a-project-id");
    hub.onNotification(UUID.randomUUID().toString().toUpperCase());
    hub.broadcastResync();

    assertEquals(4, resyncs.get());
  }

  @Test
  void isolatesFailingSubscribers() {
    ProjectInvalidationHub hub = new ProjectInvalidationHub();
    hub.subscribe(
        ignored -> {
          throw new IllegalStateException("change failure");
        },
        () -> {
          throw new IllegalStateException("resync failure");
        });
    List<UUID> changed = new ArrayList<>();
    AtomicInteger resyncs = new AtomicInteger();
    hub.subscribe(changed::add, resyncs::incrementAndGet);
    UUID projectId = UUID.randomUUID();

    hub.onNotification(projectId.toString());
    hub.broadcastResync();

    assertEquals(List.of(projectId), changed);
    assertEquals(1, resyncs.get());
  }
}
