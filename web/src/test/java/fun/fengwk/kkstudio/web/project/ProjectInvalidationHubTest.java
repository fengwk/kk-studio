package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

class ProjectInvalidationHubTest {

  @Test
  void publishesProjectChangedEventWithNonNullId() {
    AtomicReference<Object> published = new AtomicReference<>();
    ApplicationEventPublisher publisher = published::set;
    ProjectInvalidationHub hub = new ProjectInvalidationHub(publisher);

    UUID projectId = UUID.randomUUID();
    hub.publishChange(projectId);

    assertNotNull(published.get());
    assertInstanceOf(ProjectInvalidationHub.ProjectChangedEvent.class, published.get());
    ProjectInvalidationHub.ProjectChangedEvent event =
        (ProjectInvalidationHub.ProjectChangedEvent) published.get();
    assertEquals(projectId, event.getProjectId());
    assertEquals(hub, event.getSource());
  }

  @Test
  void rejectsNullProjectId() {
    ProjectInvalidationHub hub = new ProjectInvalidationHub(event -> {});
    assertThrows(NullPointerException.class, () -> hub.publishChange(null));
  }
}
