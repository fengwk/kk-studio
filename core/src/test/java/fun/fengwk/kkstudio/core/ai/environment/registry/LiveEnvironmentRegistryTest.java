package fun.fengwk.kkstudio.core.ai.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.time.Instant;
import java.util.List;

/** 基于 EnvironmentId 的占用、display name 复用以及 live registry 的 READY 生命周期。 */
class LiveEnvironmentRegistryTest {

  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final EnvironmentId DEV_ID =
      new EnvironmentId("0f8fad5b-d9cb-469f-a165-70867728950e");
  private static final EnvironmentId PROD_ID =
      new EnvironmentId("1f8fad5b-d9cb-469f-a165-70867728950e");

  @Test
  void firstIdWinsAndDisconnectRemovesEntry() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertTrue(registry.tryBind(DEV_ID, "dev", first, NOW));
    assertFalse(registry.tryBind(DEV_ID, "dev", second, NOW));
    registry.updateSkills(DEV_ID, first, List.of(), NOW);
    registry.markReady(DEV_ID, first, NOW);
    assertTrue(registry.isReady(DEV_ID));

    registry.unregister(DEV_ID, first);
    assertFalse(registry.find(DEV_ID).isPresent());
    assertTrue(registry.tryBind(DEV_ID, "dev", second, NOW));
    assertEquals(LiveEnvironmentStatus.CONNECTING, registry.find(DEV_ID).orElseThrow().status());
  }

  @Test
  void sameDisplayNameOnTwoIdsIsAllowedAndNamesRemainDisplayOnly() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertTrue(registry.tryBind(DEV_ID, "shared-name", first, NOW));
    assertTrue(registry.tryBind(PROD_ID, "shared-name", second, NOW));
    assertEquals(2, registry.list().size());
    assertEquals("shared-name", registry.find(DEV_ID).orElseThrow().name());
    assertEquals("shared-name", registry.find(PROD_ID).orElseThrow().name());
    assertTrue(registry.find(DEV_ID).orElseThrow().id().equals(DEV_ID));
    assertTrue(registry.find(PROD_ID).orElseThrow().id().equals(PROD_ID));
  }

  @Test
  void sameIdReconnectWithChangedNameIsAcceptedAfterUnregister() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertTrue(registry.tryBind(DEV_ID, "old-name", first, NOW));
    registry.unregister(DEV_ID, first);
    assertTrue(registry.tryBind(DEV_ID, "new-name", second, NOW));
    assertEquals("new-name", registry.find(DEV_ID).orElseThrow().name());
    assertTrue(registry.find(DEV_ID).orElseThrow().id().equals(DEV_ID));
  }

  @Test
  void nameMismatchAfterBindDoesNotRekeyTheEntry() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection connection = new FakeConnection("c1");

    assertTrue(registry.tryBind(DEV_ID, "bound-name", connection, NOW));
    registry.markReady(DEV_ID, connection, NOW);
    // 之后使用同一 id 但不同名称的连接不能替换已绑定项：占用判断仅依据 id。
    assertFalse(registry.tryBind(DEV_ID, "other-name", new FakeConnection("c2"), NOW));
    assertEquals("bound-name", registry.find(DEV_ID).orElseThrow().name());
    assertTrue(registry.isReady(DEV_ID));
  }

  @Test
  void foreignConnectionCannotMutateOrUnregister() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection owner = new FakeConnection("c1");
    FakeConnection foreign = new FakeConnection("c2");

    assertTrue(registry.tryBind(DEV_ID, "dev", owner, NOW));
    assertThrows(
        IllegalStateException.class, () -> registry.updateSkills(DEV_ID, foreign, List.of(), NOW));
    registry.unregister(DEV_ID, foreign);
    assertTrue(registry.find(DEV_ID).isPresent());
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
