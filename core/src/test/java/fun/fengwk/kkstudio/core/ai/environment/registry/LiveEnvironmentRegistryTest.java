package fun.fengwk.kkstudio.core.ai.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;

import java.time.Instant;
import java.util.List;

/** First-wins occupancy and READY lifecycle for the in-memory Environment registry. */
class LiveEnvironmentRegistryTest {

  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

  @Test
  void firstNameWinsAndDisconnectRemovesEntry() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertTrue(registry.tryBind("dev", first, NOW));
    assertFalse(registry.tryBind("dev", second, NOW));
    registry.updateSkills("dev", first, List.of(), NOW);
    registry.markReady("dev", first, NOW);
    assertTrue(registry.isReady("dev"));

    registry.unregister("dev", first);
    assertFalse(registry.find("dev").isPresent());
    assertTrue(registry.tryBind("dev", second, NOW));
    assertEquals(LiveEnvironmentStatus.CONNECTING, registry.find("dev").orElseThrow().status());
  }

  private static final class FakeConnection implements EnvironmentDaemonConnection {
    private final String id;
    private boolean closed;

    private FakeConnection(String id) {
      this.id = id;
    }

    @Override
    public String connectionId() {
      return id;
    }

    @Override
    public boolean isOpen() {
      return !closed;
    }

    @Override
    public void sendText(String text) {}

    @Override
    public void close() {
      closed = true;
    }
  }
}
