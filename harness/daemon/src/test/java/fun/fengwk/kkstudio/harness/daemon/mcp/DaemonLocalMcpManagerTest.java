package fun.fengwk.kkstudio.harness.daemon.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpException;
import fun.fengwk.kkstudio.harness.mcp.McpToolCallResult;
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** DaemonLocalMcpManager 生命周期、代际与配置一致性测试。 */
class DaemonLocalMcpManagerTest {

  private static final String SERVER_ID = "11111111-1111-4111-8111-111111111111";
  private static final String ENVIRONMENT_ID = "22222222-2222-4222-8222-222222222222";

  private static final DaemonLocalMcpConfig CONFIG_V1 =
      config(SERVER_ID, 1, List.of("node", "server.js"), "/opt/test", Map.of(), true, 30_000L);

  private static final DaemonLocalMcpConfig CONFIG_V2 =
      config(SERVER_ID, 2, List.of("node", "server2.js"), "/opt/test", Map.of(), true, 30_000L);

  private static DaemonLocalMcpConfig config(
      String serverId,
      long version,
      List<String> command,
      String cwd,
      Map<String, String> env,
      boolean enabled,
      long timeoutMillis) {
    return new DaemonLocalMcpConfig(
        serverId, version, ENVIRONMENT_ID, command, cwd, env, enabled, timeoutMillis);
  }

  /** 同一 (serverId, configVersion) 必须共享同一个 lazy client，且只在首次调用时创建。 */
  @Test
  void sharesSameClientForSameServerAndVersion() {
    AtomicInteger createCount = new AtomicInteger(0);
    List<FakeMcpClient> createdClients = new ArrayList<>();

    DaemonLocalMcpManager manager = manager(createCount, createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    try (DaemonLocalMcpManager.ManagedCall call1 = manager.acquire(CONFIG_V1);
        DaemonLocalMcpManager.ManagedCall call2 = manager.acquire(CONFIG_V1)) {
      assertSame(call1.getClient(deadline), call2.getClient(deadline));
      assertEquals(1, createCount.get());
    }

    manager.close();
    assertTrue(createdClients.getFirst().closed.get());
  }

  /** 新版本准入必须 fence 旧版本新调用，并在旧版本 active 调用归零后关闭其进程。 */
  @Test
  void versionUpgradeFencesOldVersionAndDrainsActiveCalls() {
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(new AtomicInteger(0), createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall callV1 = manager.acquire(CONFIG_V1);
    FakeMcpClient clientV1 = (FakeMcpClient) callV1.getClient(deadline);
    assertFalse(clientV1.closed.get());

    // 准入 V2 触发 V1 fencing
    DaemonLocalMcpManager.ManagedCall callV2 = manager.acquire(CONFIG_V2);
    FakeMcpClient clientV2 = (FakeMcpClient) callV2.getClient(deadline);
    assertNotSame(clientV1, clientV2);
    assertFalse(clientV1.closed.get(), "V1 仍有 active 调用，必须等待归零");

    assertThrows(DaemonMcpFencedException.class, () -> manager.acquire(CONFIG_V1));

    callV1.release(false);
    assertTrue(clientV1.closed.get(), "active 调用归零后旧版本进程必须关闭");
    assertFalse(clientV2.closed.get());

    callV2.release(false);
    manager.close();
    assertTrue(clientV2.closed.get());
  }

  /** 同一 (serverId, configVersion) 出现不同解析配置时必须 fail closed：静默沿用旧进程会让新调用以错误身份执行。 */
  @Test
  void sameVersionWithDifferentConfigIsFenced() {
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(new AtomicInteger(0), createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall call = manager.acquire(CONFIG_V1);
    FakeMcpClient first = (FakeMcpClient) call.getClient(deadline);

    // 同版本但 command/cwd/env 任一不同都不得改绑到旧实例
    DaemonLocalMcpConfig driftedCommand =
        config(SERVER_ID, 1, List.of("node", "other.js"), "/opt/test", Map.of(), true, 30_000L);
    DaemonLocalMcpConfig driftedCwd =
        config(SERVER_ID, 1, List.of("node", "server.js"), "/opt/other", Map.of(), true, 30_000L);
    DaemonLocalMcpConfig driftedEnv =
        config(
            SERVER_ID,
            1,
            List.of("node", "server.js"),
            "/opt/test",
            Map.of("SECRET_NAME", "super_secret_value"),
            true,
            30_000L);
    DaemonLocalMcpConfig driftedTimeout =
        config(SERVER_ID, 1, List.of("node", "server.js"), "/opt/test", Map.of(), true, 1_000L);

    for (DaemonLocalMcpConfig drifted :
        List.of(driftedCommand, driftedCwd, driftedEnv, driftedTimeout)) {
      DaemonMcpFencedException error =
          assertThrows(DaemonMcpFencedException.class, () -> manager.acquire(drifted));
      // 异常文本绝不回显配置内容
      assertFalse(error.getMessage().contains("SECRET_NAME"));
      assertFalse(error.getMessage().contains("super_secret_value"));
      assertFalse(error.getMessage().contains("/opt/test"));
    }

    // 已准入的实例不受影响
    assertFalse(first.closed.get());
    call.release(false);
    manager.close();
  }

  /** 执行失败的 client 必须销毁并允许后续重建，且不自动重试失败调用。 */
  @Test
  void failedClientIsDestroyedAndRebuiltOnNextCall() {
    AtomicInteger createCount = new AtomicInteger(0);
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(createCount, createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall call1 = manager.acquire(CONFIG_V1);
    FakeMcpClient client1 = (FakeMcpClient) call1.getClient(deadline);
    call1.release(true);
    assertTrue(client1.closed.get());

    DaemonLocalMcpManager.ManagedCall call2 = manager.acquire(CONFIG_V1);
    FakeMcpClient client2 = (FakeMcpClient) call2.getClient(deadline);
    assertNotSame(client1, client2);
    assertEquals(2, createCount.get());

    call2.release(false);
    manager.close();
  }

  /** 已经取得旧实例许可、但尚未取 client 的并发调用，在该实例被标记失败后必须拒绝执行；不得在失败实例内部覆盖并泄漏旧 client。 */
  @Test
  void delayedCallCannotReinitializeFailedInstance() {
    AtomicInteger createCount = new AtomicInteger(0);
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(createCount, createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall failing = manager.acquire(CONFIG_V1);
    DaemonLocalMcpManager.ManagedCall delayed = manager.acquire(CONFIG_V1);
    FakeMcpClient failedClient = (FakeMcpClient) failing.getClient(deadline);
    failing.release(true);

    assertThrows(McpException.class, () -> delayed.getClient(deadline));
    assertEquals(1, createCount.get(), "失败实例内部不得悄悄创建 replacement");
    assertFalse(failedClient.closed.get(), "仍有 active 许可时旧 client 必须等待 drain");

    delayed.release(false);
    assertTrue(failedClient.closed.get(), "最后一个旧许可释放后必须关闭失败 client");

    DaemonLocalMcpManager.ManagedCall replacement = manager.acquire(CONFIG_V1);
    assertNotSame(failedClient, replacement.getClient(deadline));
    assertEquals(2, createCount.get());
    replacement.release(false);
    manager.close();
  }

  /** 不同 server 各自独立共存；一个 server 的版本升级不影响另一个。 */
  @Test
  void differentServersAreIndependent() {
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(new AtomicInteger(0), createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpConfig otherServer =
        new DaemonLocalMcpConfig(
            "33333333-3333-4333-8333-333333333333",
            1,
            ENVIRONMENT_ID,
            List.of("node", "other.js"),
            "/opt/test",
            Map.of(),
            true,
            30_000L);

    DaemonLocalMcpManager.ManagedCall first = manager.acquire(CONFIG_V1);
    DaemonLocalMcpManager.ManagedCall second = manager.acquire(otherServer);
    FakeMcpClient firstClient = (FakeMcpClient) first.getClient(deadline);
    FakeMcpClient secondClient = (FakeMcpClient) second.getClient(deadline);
    assertNotSame(firstClient, secondClient);

    // 升级第一个 server 的版本不得影响第二个
    DaemonLocalMcpManager.ManagedCall upgraded = manager.acquire(CONFIG_V2);
    assertFalse(secondClient.closed.get());
    assertFalse(upgraded.getClient(deadline).equals(firstClient));

    first.release(false);
    second.release(false);
    upgraded.release(false);
    manager.close();
  }

  /** 停机时全量关闭所有 server 的所有版本进程，不留孤儿。 */
  @Test
  void closeShutsDownEveryServer() {
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(new AtomicInteger(0), createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall call = manager.acquire(CONFIG_V1);
    call.getClient(deadline);

    manager.close();
    assertTrue(createdClients.getFirst().closed.get());

    // close 之后再申请必须失败，而不是创建新的子进程
    assertThrows(IllegalStateException.class, () -> manager.acquire(CONFIG_V1));
  }

  /**
   * 失败实例在仍有 active 调用时，绝不能被立即关闭：新调用必须拿到重建实例，而旧实例要等最后一个 active 调用 release 后才关闭。
   *
   * <p>这是 drain 语义最容易写错的边界——若在 acquire 时直接 close 旧实例，会打断仍在执行的并发调用。
   */
  @Test
  void failedInstanceWithActiveCallsIsDrainedNotClosedEarly() {
    AtomicInteger createCount = new AtomicInteger(0);
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(createCount, createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall call1 = manager.acquire(CONFIG_V1);
    DaemonLocalMcpManager.ManagedCall call2 = manager.acquire(CONFIG_V1);
    FakeMcpClient oldClient = (FakeMcpClient) call1.getClient(deadline);
    assertSame(oldClient, call2.getClient(deadline));

    // 第一个调用标记失败：实例脱钩，但第二个调用仍在执行
    call1.release(true);
    assertFalse(oldClient.closed.get(), "仍有 active 调用时绝不能提前关闭失败实例");

    // 新调用必须重建实例，而旧实例继续服务在途调用
    DaemonLocalMcpManager.ManagedCall call3 = manager.acquire(CONFIG_V1);
    FakeMcpClient newClient = (FakeMcpClient) call3.getClient(deadline);
    assertNotSame(oldClient, newClient);
    assertFalse(oldClient.closed.get());
    assertFalse(newClient.closed.get());

    // 旧实例的最后一个 active 调用释放后才关闭
    call2.release(false);
    assertTrue(oldClient.closed.get(), "最后一个 active 调用释放后旧实例必须关闭");

    call3.release(false);
    manager.close();
    assertTrue(newClient.closed.get());
  }

  /**
   * 已 fence 的旧代际在关闭后必须脱离代际表：无论它在升级时已经空闲，还是等到 active 归零后才关闭。
   *
   * <p>否则每次版本升级都会永久留下一条已关闭的记录，长跑进程中会无界增长。
   */
  @Test
  void retiredGenerationsAreRemovedFromTracking() {
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(new AtomicInteger(0), createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    // 场景 A：升级时旧代际已经空闲 → 必须立即关闭并移除
    DaemonLocalMcpManager.ManagedCall v1 = manager.acquire(CONFIG_V1);
    FakeMcpClient client1 = (FakeMcpClient) v1.getClient(deadline);
    v1.release(false);
    assertEquals(1, manager.retainedInstanceCount());

    DaemonLocalMcpManager.ManagedCall v2 = manager.acquire(CONFIG_V2);
    v2.getClient(deadline);
    assertTrue(client1.closed.get(), "空闲的旧代际必须立即关闭");
    assertEquals(1, manager.retainedInstanceCount(), "空闲的旧代际必须同时脱离代际表");

    // 场景 B：升级时旧代际仍有 active 调用 → active 归零后关闭并移除
    DaemonLocalMcpConfig v3 =
        config(SERVER_ID, 3, List.of("node", "server3.js"), "/opt/test", Map.of(), true, 30_000L);
    DaemonLocalMcpManager.ManagedCall v3Call = manager.acquire(v3);
    v3Call.getClient(deadline);
    assertEquals(2, manager.retainedInstanceCount(), "V2 仍在服务在途调用，必须保留");

    v2.release(false);
    assertEquals(1, manager.retainedInstanceCount(), "V2 归零关闭后必须脱离代际表");

    // 释放 V3 的在途调用：它是最新代际，空闲后保留是正确行为
    v3Call.release(false);
    assertEquals(1, manager.retainedInstanceCount());

    // 连续多代升级也不允许任何积累
    long previous = 0;
    for (long version = 4; version <= 12; version++) {
      DaemonLocalMcpConfig next =
          config(
              SERVER_ID,
              version,
              List.of("node", "server" + version + ".js"),
              "/opt/test",
              Map.of(),
              true,
              30_000L);
      DaemonLocalMcpManager.ManagedCall nextCall = manager.acquire(next);
      nextCall.getClient(deadline);
      previous = manager.retainedInstanceCount();
      nextCall.release(false);
    }
    assertEquals(1, previous, "多代升级后只允许保留当前代际");

    manager.close();
    assertEquals(0, manager.retainedInstanceCount());
  }

  /** 同版本的新实例不得被旧实例的 release 反注册移除：否则同版本调用会被迫反复重建进程。 */
  @Test
  void failedReplacementIsNotEvictedByOldInstanceRelease() {
    AtomicInteger createCount = new AtomicInteger(0);
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(createCount, createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall first = manager.acquire(CONFIG_V1);
    first.getClient(deadline);
    first.release(true);

    DaemonLocalMcpManager.ManagedCall second = manager.acquire(CONFIG_V1);
    FakeMcpClient replacement = (FakeMcpClient) second.getClient(deadline);

    // 旧实例已经 release 过；同版本第三次调用必须复用 replacement，而不是再次重建
    DaemonLocalMcpManager.ManagedCall third = manager.acquire(CONFIG_V1);
    assertSame(replacement, third.getClient(deadline));
    assertEquals(2, createCount.get(), "失败实例只允许重建一次");

    second.release(false);
    third.release(false);
    manager.close();
  }

  /** manager 关闭后并发的 acquire 必须失败，且绝不能在停机后拉起新进程（acquire/close 竞态）。 */
  @Test
  void acquireAfterCloseNeverCreatesNewInstance() {
    AtomicInteger createCount = new AtomicInteger(0);
    List<FakeMcpClient> createdClients = new ArrayList<>();
    DaemonLocalMcpManager manager = manager(createCount, createdClients);
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(10));

    DaemonLocalMcpManager.ManagedCall call = manager.acquire(CONFIG_V1);
    call.getClient(deadline);
    manager.close();

    for (int attempt = 0; attempt < 5; attempt++) {
      assertThrows(IllegalStateException.class, () -> manager.acquire(CONFIG_V1));
    }
    // 关闭后不得再创建任何实例
    assertEquals(0, manager.retainedInstanceCount());
    assertTrue(createdClients.getFirst().closed.get());
    call.release(false);
    assertEquals(1, createCount.get());
  }

  private static DaemonLocalMcpManager manager(
      AtomicInteger createCount, List<FakeMcpClient> createdClients) {
    return new DaemonLocalMcpManager(
        (config, deadline) -> {
          createCount.incrementAndGet();
          FakeMcpClient client = new FakeMcpClient();
          createdClients.add(client);
          return client;
        });
  }

  private static final class FakeMcpClient implements McpClient {

    final AtomicBoolean closed = new AtomicBoolean(false);

    @Override
    public List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token) {
      return List.of();
    }

    @Override
    public McpToolCallResult callTool(
        String toolName, String argumentsJson, McpDeadline deadline, McpCancellationToken token) {
      return McpToolCallResult.success(List.of(), "{}");
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }
}
