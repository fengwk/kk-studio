package fun.fengwk.kkstudio.platform.environment.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonConnection;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 基于 canonical EnvironmentName 的占用、断开/租约释放接管与 READY + 心跳过期可用性规则。 */
class LiveEnvironmentRegistryTest {

  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(60);
  private static final EnvironmentName DEV = new EnvironmentName("dev");
  private static final EnvironmentName PROD = new EnvironmentName("prod");
  private static final DaemonCapabilities CAPABILITIES =
      new DaemonCapabilities(
          DaemonCapabilities.VERSION,
          new DaemonEnvironmentInfo(
              DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
          List.of(),
          List.of());

  private static BindResult bind(
      LiveEnvironmentRegistry registry,
      EnvironmentName name,
      EnvironmentDaemonConnection connection,
      Instant now) {
    return registry.tryBind(name, connection, now, HEARTBEAT_TIMEOUT);
  }

  @Test
  void firstNameWinsAndDisconnectRemovesEntry() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    assertInstanceOf(BindResult.Rejected.class, bind(registry, DEV, second, NOW));
    registry.updateCapabilities(DEV, first, CAPABILITIES, NOW);
    registry.markReady(DEV, first, NOW);
    assertTrue(registry.isReady(DEV, NOW, HEARTBEAT_TIMEOUT));

    registry.unregister(DEV, first);
    assertFalse(registry.find(DEV).isPresent());
    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, second, NOW));
    assertEquals(LiveEnvironmentStatus.CONNECTING, registry.find(DEV).orElseThrow().status());
  }

  @Test
  void distinctNamesAreIndependentEntries() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    assertInstanceOf(BindResult.Accepted.class, bind(registry, PROD, second, NOW));
    assertEquals(2, registry.list().size());
    assertEquals(DEV, registry.find(DEV).orElseThrow().name());
    assertEquals(PROD, registry.find(PROD).orElseThrow().name());
  }

  @Test
  void sameConnectionRebindIsIdempotentAccepted() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    // 同连接幂等重绑：继续持有，绝不挤占自己。
    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    assertEquals("c1", registry.find(DEV).orElseThrow().connection().connectionId());
  }

  @Test
  void freshConnectingClaimIsNotStolen() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    // CONNECTING 的新鲜声明（连接打开 + 心跳未过期）与 READY 同等受保护：不能因为未 READY 就被抢走。
    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    assertEquals(null, registry.find(DEV).orElseThrow().capabilities());
    assertThrows(IllegalStateException.class, () -> registry.markReady(DEV, first, NOW));
    assertInstanceOf(BindResult.Rejected.class, bind(registry, DEV, second, NOW));
    assertEquals("c1", registry.find(DEV).orElseThrow().connection().connectionId());
  }

  @Test
  void closedHolderConnectionIsAtomicallyReplaced() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    registry.updateCapabilities(DEV, first, CAPABILITIES, NOW);
    registry.markReady(DEV, first, NOW);
    first.close();

    BindResult result = bind(registry, DEV, second, NOW);
    BindResult.Replaced replaced = assertInstanceOf(BindResult.Replaced.class, result);
    // 被替换的是旧连接；registry 条目已原子切换到新 holder。
    assertEquals("c1", replaced.displacedConnection().connectionId());
    assertEquals("c2", registry.find(DEV).orElseThrow().connection().connectionId());
    // 旧连接的 unregister 不得移除新 holder。
    registry.unregister(DEV, first);
    assertEquals("c2", registry.find(DEV).orElseThrow().connection().connectionId());
    // 新 holder 可正常升级 READY。
    registry.updateCapabilities(DEV, second, CAPABILITIES, NOW);
    registry.markReady(DEV, second, NOW);
    assertTrue(registry.isReady(DEV, NOW, HEARTBEAT_TIMEOUT));
  }

  @Test
  void expiredHeartbeatLeaseIsAtomicallyReplacedEvenWhileOpen() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    registry.updateCapabilities(DEV, first, CAPABILITIES, NOW);
    registry.markReady(DEV, first, NOW);
    Instant stale = NOW.plus(HEARTBEAT_TIMEOUT).plusSeconds(1);

    // 连接仍打开但心跳租约过期：同一可用性规则判定可接管。
    BindResult result = bind(registry, DEV, second, stale);
    BindResult.Replaced replaced = assertInstanceOf(BindResult.Replaced.class, result);
    assertEquals("c1", replaced.displacedConnection().connectionId());
    assertEquals("c2", registry.find(DEV).orElseThrow().connection().connectionId());
    assertTrue(first.isOpen(), "displaced connection ownership is handed to the caller to close");
  }

  @Test
  void freshHeartbeatAfterTimeoutStillProtectsHolder() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection first = new FakeConnection("c1");
    FakeConnection second = new FakeConnection("c2");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, first, NOW));
    registry.updateCapabilities(DEV, first, CAPABILITIES, NOW);
    registry.markReady(DEV, first, NOW);
    Instant refreshed = NOW.plus(HEARTBEAT_TIMEOUT).minusSeconds(1);
    registry.heartbeat(DEV, first, refreshed);

    // 心跳在超时窗口内刷新过：租约未过期，冲突。
    assertInstanceOf(BindResult.Rejected.class, bind(registry, DEV, second, refreshed));
    assertEquals("c1", registry.find(DEV).orElseThrow().connection().connectionId());
  }

  @Test
  void staleHeartbeatMakesReadyEnvironmentUnavailable() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection connection = new FakeConnection("c1");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, connection, NOW));
    registry.updateCapabilities(DEV, connection, CAPABILITIES, NOW);
    registry.markReady(DEV, connection, NOW);
    assertTrue(registry.isReady(DEV, NOW, HEARTBEAT_TIMEOUT));
    // 心跳超过超时未刷新：同一规则下不可用。
    Instant stale = NOW.plus(HEARTBEAT_TIMEOUT).plusSeconds(1);
    assertFalse(registry.isReady(DEV, stale, HEARTBEAT_TIMEOUT));
    // 刷新心跳后重新可用。
    registry.heartbeat(DEV, connection, stale);
    assertTrue(registry.isReady(DEV, stale, HEARTBEAT_TIMEOUT));
  }

  @Test
  void closedConnectionMakesReadyEnvironmentUnavailable() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection connection = new FakeConnection("c1");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, connection, NOW));
    registry.updateCapabilities(DEV, connection, CAPABILITIES, NOW);
    registry.markReady(DEV, connection, NOW);
    connection.close();
    assertFalse(registry.isReady(DEV, NOW, HEARTBEAT_TIMEOUT));
  }

  @Test
  void foreignConnectionCannotMutateOrUnregister() {
    LiveEnvironmentRegistry registry = new LiveEnvironmentRegistry();
    FakeConnection owner = new FakeConnection("c1");
    FakeConnection foreign = new FakeConnection("c2");

    assertInstanceOf(BindResult.Accepted.class, bind(registry, DEV, owner, NOW));
    assertThrows(
        IllegalStateException.class,
        () -> registry.updateCapabilities(DEV, foreign, CAPABILITIES, NOW));
    registry.unregister(DEV, foreign);
    assertTrue(registry.find(DEV).isPresent());
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
    public boolean sendText(String text) {
      return true;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
