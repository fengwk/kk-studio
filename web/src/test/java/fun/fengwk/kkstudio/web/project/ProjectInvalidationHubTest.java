package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.project.notification.ProjectNotifications;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    hub.onNotification(projectId);
    assertEquals(List.of(projectId), changed);
    assertEquals(0, resyncs.get());

    subscription.close();
    hub.onNotification(projectId);
    assertEquals(List.of(projectId), changed);
  }

  @Test
  void invalidPayloadAndReconnectTriggerResync() {
    ProjectInvalidationHub hub = new ProjectInvalidationHub();
    AtomicInteger resyncs = new AtomicInteger();
    hub.subscribe(ignored -> {}, resyncs::incrementAndGet);

    List<String> invalidPayloads =
        List.of("not-a-project-id", UUID.randomUUID().toString().toUpperCase(Locale.ROOT), "");

    for (String invalid : invalidPayloads) {
      assertThrows(
          RuntimeException.class,
          () ->
              ProjectNotifications.ISSUE_CHANGED
                  .codec()
                  .decode(invalid.getBytes(StandardCharsets.UTF_8)),
          "总线 codec 必须拒绝非法 payload: " + invalid);
      hub.broadcastResync();
    }
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

    hub.onNotification(projectId);
    hub.broadcastResync();

    assertEquals(List.of(projectId), changed);
    assertEquals(1, resyncs.get());
  }
}
