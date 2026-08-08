package fun.fengwk.kkstudio.harness.daemon.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerStatus;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpToolDescriptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** registry 生命周期：独立失败启动、快照冻结与 client 恰好关闭一次。 */
class McpServerRegistryTest {

  @Test
  void independentStartupFailureKeepsOthersReady() {
    CountingFactory factory = new CountingFactory();
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(stdio("ok-a"), stdio("bad"), stdio("ok-b"))),
            factory,
            Duration.ofSeconds(10));
    registry.start();

    List<DaemonMcpServerDescriptor> snapshot = registry.snapshot();
    assertEquals(List.of("ok-a", "bad", "ok-b"), names(snapshot));
    assertEquals(DaemonMcpServerStatus.READY, snapshot.get(0).status());
    assertEquals(DaemonMcpServerStatus.FAILED, snapshot.get(1).status());
    assertTrue(snapshot.get(1).error() != null);
    assertTrue(snapshot.get(1).tools().isEmpty());
    assertEquals(DaemonMcpServerStatus.READY, snapshot.get(2).status());
    assertEquals(List.of("t1", "t2"), toolNames(snapshot.get(0)));

    assertEquals(3, factory.creates.get());
    assertTrue(registry.find("ok-a").isPresent());
    assertTrue(registry.find("ok-b").isPresent());
    assertTrue(registry.find("bad").isEmpty());
    assertTrue(registry.find("missing").isEmpty());
  }

  @Test
  void closeClosesEveryCreatedClientExactlyOnce() {
    CountingFactory factory = new CountingFactory();
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(stdio("ok-a"), stdio("bad"), stdio("ok-b"))),
            factory,
            Duration.ofSeconds(10));
    registry.start();

    registry.close();
    registry.close();

    assertEquals(2, factory.closedClients.get());
    assertEquals(0, factory.openClients.get());
  }

  @Test
  void closeAttemptsEveryClientEvenIfOneCloseThrows() {
    CountingFactory factory = new CountingFactory();
    factory.closeThrowsOn.add("ok-a");
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(stdio("ok-a"), stdio("ok-b"), stdio("ok-c"))),
            factory,
            Duration.ofSeconds(10));
    registry.start();

    registry.close();

    // 首个 client close 抛错被吸收，后续 client 仍被恰好关闭一次。
    assertEquals(3, factory.closedClients.get());
    assertEquals(0, factory.openClients.get());
  }

  @Test
  void listToolsFailureClosesCreatedClientOnceAndRecordsFailed() {
    CountingFactory factory = new CountingFactory();
    factory.listToolsThrowsOn.add("bad-list");
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(stdio("bad-list"), stdio("ok"))),
            factory,
            Duration.ofSeconds(10));
    registry.start();

    DaemonMcpServerDescriptor failed = registry.snapshot().get(0);
    assertEquals(DaemonMcpServerStatus.FAILED, failed.status());
    assertTrue(failed.error().contains("list failed"));
    assertTrue(failed.tools().isEmpty());
    assertEquals(DaemonMcpServerStatus.READY, registry.snapshot().get(1).status());
    // 已创建但 listTools 失败的 client 被恰好关闭一次；正常 server 的 client 保持打开。
    assertEquals(1, factory.closedClients.get());
    assertEquals(1, factory.openClients.get());
  }

  @Test
  void emptyRegistryCreatesNothingAndClosesSafely() {
    McpServerRegistry registry = McpServerRegistry.empty();
    assertEquals(0, registry.snapshot().size());
    assertTrue(registry.find("anything").isEmpty());
    registry.start();
    registry.close();
  }

  @Test
  void startIsIdempotent() {
    CountingFactory factory = new CountingFactory();
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(stdio("ok-a"))), factory, Duration.ofSeconds(10));
    registry.start();
    registry.start();
    assertEquals(1, factory.creates.get());
  }

  @Test
  void sanitizesFailedErrorMessageToSingleLineBounded() {
    String huge = "line1\nline2\r\n" + "x".repeat(DaemonMcpServerDescriptor.MAX_ERROR_CHARS * 2);
    McpServerClientFactory exploding =
        (config, timeout) -> {
          throw new IllegalStateException(huge);
        };
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(stdio("bad"))), exploding, Duration.ofSeconds(10));
    registry.start();

    DaemonMcpServerDescriptor failed = registry.snapshot().get(0);
    assertNotNull(failed.error());
    assertFalse(failed.error().contains("\n"));
    assertTrue(failed.error().length() <= DaemonMcpServerDescriptor.MAX_ERROR_CHARS);
    assertTrue(failed.error().startsWith("line1 line2 "));
  }

  @Test
  void failedErrorNeverLeaksHeadersEnvironmentCommandOrUrl() {
    McpServerConfig httpServer =
        new McpServerConfig(
            "remote",
            McpTransportType.STREAMABLE_HTTP,
            null,
            null,
            null,
            "https://mcp.secret.example/mcp",
            Map.of("Authorization", "Bearer super-secret-token-123"));
    McpServerConfig stdioServer =
        new McpServerConfig(
            "stdio-secret",
            McpTransportType.STDIO,
            null,
            List.of("npx"),
            Map.of("TOKEN", "stdio-secret-env"),
            null,
            null);
    String leakyMessage =
        "connect to https://mcp.secret.example/mcp with Bearer super-secret-token-123 "
            + "via npx mcp-server failed; env=stdio-secret-env";
    McpServerClientFactory exploding =
        (config, timeout) -> {
          throw new IllegalStateException(leakyMessage);
        };
    McpServerRegistry registry =
        new McpServerRegistry(
            new McpConfig(List.of(httpServer, stdioServer)), exploding, Duration.ofSeconds(10));
    registry.start();

    String failedError = registry.snapshot().get(0).error();
    assertFalse(failedError.contains("super-secret-token-123"), failedError);
    assertFalse(failedError.contains("https://mcp.secret.example/mcp"), failedError);
    // stdio 配置的 environment 值同样被剔除。
    String stdioError = registry.snapshot().get(1).error();
    assertFalse(stdioError.contains("stdio-secret-env"), stdioError);
  }

  private static McpServerConfig stdio(String name) {
    return new McpServerConfig(
        name, McpTransportType.STDIO, null, List.of("echo"), null, null, null);
  }

  private static List<String> names(List<DaemonMcpServerDescriptor> servers) {
    return servers.stream().map(server -> server.name()).toList();
  }

  private static List<String> toolNames(DaemonMcpServerDescriptor server) {
    return server.tools().stream().map(DaemonMcpToolDescriptor::name).toList();
  }

  private static final class CountingFactory implements McpServerClientFactory {

    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger closedClients = new AtomicInteger();
    private final AtomicInteger openClients = new AtomicInteger();
    private final List<FakeClient> clients = new ArrayList<>();
    private final List<String> closeThrowsOn = new ArrayList<>();
    private final List<String> listToolsThrowsOn = new ArrayList<>();

    @Override
    public McpServerClient create(McpServerConfig config, Duration defaultTimeout) {
      creates.incrementAndGet();
      if (config.name().equals("bad")) {
        throw new IllegalStateException("cannot start " + config.name());
      }
      FakeClient client =
          new FakeClient(
              config.name(),
              new McpToolSpec("t1", "tool one", "{\"name\":\"t1\"}"),
              new McpToolSpec("t2", "tool two", "{\"name\":\"t2\"}"),
              closedClients,
              openClients,
              closeThrowsOn.contains(config.name()),
              listToolsThrowsOn.contains(config.name()));
      clients.add(client);
      return client;
    }
  }

  private static final class FakeClient implements McpServerClient {

    private final String name;
    private final List<McpToolSpec> tools;
    private final AtomicInteger closedClients;
    private final AtomicInteger openClients;
    private final boolean closeThrows;
    private final boolean listToolsThrows;

    private FakeClient(
        String name,
        McpToolSpec first,
        McpToolSpec second,
        AtomicInteger closedClients,
        AtomicInteger openClients,
        boolean closeThrows,
        boolean listToolsThrows) {
      this.name = name;
      this.tools = List.of(first, second);
      this.closedClients = closedClients;
      this.openClients = openClients;
      this.closeThrows = closeThrows;
      this.listToolsThrows = listToolsThrows;
      this.openClients.incrementAndGet();
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<McpToolSpec> listTools() {
      if (listToolsThrows) {
        throw new IllegalStateException("list failed for " + name);
      }
      return tools;
    }

    @Override
    public McpCallOutcome call(McpToolRequest request) {
      return new McpCallOutcome(false, "ok");
    }

    @Override
    public void close() {
      closedClients.incrementAndGet();
      openClients.decrementAndGet();
      if (closeThrows) {
        throw new IllegalStateException("close failed for " + name);
      }
    }
  }
}
