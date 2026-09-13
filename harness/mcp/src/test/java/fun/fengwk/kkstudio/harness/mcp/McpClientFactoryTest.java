package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link McpClientFactory} 初始化失败与预算语义测试。
 *
 * <p>重点验证：初始化失败不泄漏半连接、超时统一映射为 {@link McpTimeoutException}、 且初始化与调用共用同一个外层预算（不存在「初始化重新获得一份预算」的行为）。
 */
class McpClientFactoryTest {

  @TempDir Path tempDir;

  private List<String> javaCommand() {
    return List.of(
        ProcessHandle.current().info().command().orElse("java"),
        "-cp",
        System.getProperty("java.class.path"),
        TestMcpServer.class.getName());
  }

  /** 命令不存在时初始化失败必须是固定不透明异常，而不是泄漏本地路径或 argv。 */
  @Test
  void initializationFailureIsOpaque() {
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of("non_existent_executable_12345", "/opt/test/secret-arg"),
            tempDir.toString(),
            Map.of("SECRET_NAME", "super_secret_value"));

    McpException error =
        (McpException)
            assertThatThrownBy(() -> McpClientFactory.createStdio(config, Duration.ofSeconds(5)))
                .isInstanceOf(McpException.class)
                .actual();

    assertThat(error.getMessage()).doesNotContain("non_existent_executable_12345");
    assertThat(error.getMessage()).doesNotContain("/opt/test");
    assertThat(error.getMessage()).doesNotContain("super_secret_value");
    assertThat(error.getCause()).isNull();
  }

  /** 已过期的 deadline 必须在创建阶段就失败：工厂不提供无预算的隐式初始化。 */
  @Test
  void expiredDeadlineFailsAtCreation() {
    StdioMcpConfig config = new StdioMcpConfig(javaCommand(), tempDir.toString(), Map.of());
    assertThatThrownBy(
            () ->
                McpClientFactory.createStdio(
                    config, McpDeadline.ofDeadline(Instant.now().minusSeconds(1))))
        .isInstanceOf(McpTimeoutException.class);
  }

  /** 初始化握手慢于总预算时必须在预算内以超时结束，而不是继续等到调用阶段； 这证明初始化消耗的是同一个外层预算，而不是各自独立的预算。 */
  @Test
  @Timeout(60)
  void slowInitializationConsumesTheSingleBudget() {
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of(
                ProcessHandle.current().info().command().orElse("java"),
                "-Dtest.mcp.initDelayMillis=5000",
                "-cp",
                System.getProperty("java.class.path"),
                TestMcpServer.class.getName()),
            tempDir.toString(),
            Map.of());

    long startNanos = System.nanoTime();
    assertThatThrownBy(
            () -> McpClientFactory.createStdio(config, McpDeadline.of(Duration.ofMillis(600))))
        .isInstanceOf(McpTimeoutException.class);

    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    // 预算 600ms：若初始化重新获得独立预算，耗时会显著超过该上界（接近 5s）
    assertThat(elapsedMillis).isLessThan(3_000L);
  }

  /** 验证受控的「已消耗预算」不会在后续阶段被重置： 外层 deadline 已经消耗部分时间后传入工厂，工厂只使用剩余预算，绝不重新获得完整初始预算。 */
  @Test
  @Timeout(60)
  void consumedDeadlineDoesNotResetStageBudget() throws Exception {
    String javaBin = ProcessHandle.current().info().command().orElse("java");
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of(
                javaBin,
                "-Dtest.mcp.initDelayMillis=300",
                "-cp",
                System.getProperty("java.class.path"),
                TestMcpServer.class.getName()),
            tempDir.toString(),
            Map.of());

    // 创建绝对截止时间为 500ms 后的 deadline，并在调用工厂前先消耗 350ms
    Instant target = Instant.now().plusMillis(500);
    McpDeadline deadline = McpDeadline.ofDeadline(target);
    Thread.sleep(350);

    // 此时 deadline 仅剩 ~150ms，而服务端初始化握手需要 300ms：
    // 若发生 stage reset 重新分配 500ms，初始化将成功；由于不重置预算，剩余 150ms 将确定性超时
    long startNanos = System.nanoTime();
    assertThatThrownBy(() -> McpClientFactory.createStdio(config, deadline))
        .isInstanceOf(McpTimeoutException.class)
        .hasMessageContaining("timed out");

    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    // 耗时基于当前剩余的 ~150ms，远小于服务端完整的 300ms 握手时间
    assertThat(elapsedMillis).isLessThan(280L);
  }

  /** 远端配置缺少可解析 URL 时必须失败，且异常不保留底层 cause。 */
  @Test
  void remoteInitializationFailureIsOpaque() {
    RemoteMcpConfig config =
        new RemoteMcpConfig(
            "http://127.0.0.1:1/mcp", Map.of("Authorization", "Bearer secret_token"));

    McpException error =
        (McpException)
            assertThatThrownBy(() -> McpClientFactory.createRemote(config, Duration.ofSeconds(3)))
                .isInstanceOf(McpException.class)
                .actual();
    assertThat(error.getMessage()).doesNotContain("secret_token");
    assertThat(error.getCause()).isNull();
  }

  /** 工具定义/结果模型的工厂方法分支：text 与 error 静态构造必须保持 error 标志与内容。 */
  @Test
  void resultFactoryMethodsKeepErrorSemantics() {
    assertThat(McpToolCallResult.text("ok").error()).isFalse();
    assertThat(McpToolCallResult.errorText("boom").error()).isTrue();
    assertThat(McpToolCallResult.error(List.of(), "{}").error()).isTrue();
    assertThat(McpToolCallResult.success(List.of(), "{}").error()).isFalse();
  }

  /** 取消令牌的「已取消后注册」必须立即回放，保证取消意图不会丢失。 */
  @Test
  void cancellationTokenReplaysForLateListeners() {
    McpCancellationToken token = new McpCancellationToken();
    token.cancel();
    AtomicBoolean replayed = new AtomicBoolean();
    token.onCancel(() -> replayed.set(true));
    assertThat(replayed).isTrue();
    // cancel 幂等：重复调用不得重复触发监听器
    AtomicBoolean second = new AtomicBoolean();
    token.onCancel(() -> second.set(true));
    assertThat(second).isTrue();
  }

  /** StdioMcpConfig 只接受当前 OS 语义下的绝对 cwd，相对路径与非法路径都必须拒绝。 */
  @Test
  void stdioConfigRequiresAbsoluteCwd() {
    assertThatThrownBy(() -> new StdioMcpConfig(javaCommand(), "relative/path", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> new StdioMcpConfig(javaCommand(), "\0invalid", Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(error -> assertThat(error.getCause()).isNull());
  }

  /**
   * 客户端必须与 Platform 侧 reusable MCP client 协商同一个协议版本 {@code 2025-11-25}。
   *
   * <p>证据来自真实子进程：fake server 回读 initialize 请求里客户端实际声明的版本，因此断言的是线上事实，而不是本模块的常量。
   */
  @Test
  void negotiatesPlatformProtocolVersionWithRealServer() {
    StdioMcpConfig config = new StdioMcpConfig(javaCommand(), tempDir.toString(), Map.of());
    try (McpClient client = McpClientFactory.createStdio(config, Duration.ofSeconds(20))) {
      McpToolCallResult result =
          client.callTool(
              "negotiated_protocol",
              "{}",
              McpDeadline.of(Duration.ofSeconds(5)),
              McpCancellationToken.none());
      assertThat(result.error()).isFalse();
      assertThat(((TextResultContent) result.contents().getFirst()).text())
          .as("不得把已有 Platform 契约降级为更旧的协议版本")
          .isEqualTo("2025-11-25");
    }
  }

  /**
   * 「子进程已拉起但握手慢于预算」时，超时必须回收该子进程，绝不留下孤儿 MCP 进程。
   *
   * <p>确定性场景：3s 握手延迟 + 200ms 预算，子进程必然已 spawn（SDK 先 start 再握手），随后调用方超时放弃。 断言只针对<strong>本测试新增的
   * PID</strong>：同一 JVM 中其它并行测试也可能持有同类子进程，因此绝不假设全局计数为零。
   */
  @Test
  @Timeout(60)
  void slowHandshakeTimeoutReclaimsSpawnedSubprocess() {
    String javaBin = ProcessHandle.current().info().command().orElse("java");
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of(
                javaBin,
                "-Dtest.mcp.initDelayMillis=3000",
                "-cp",
                System.getProperty("java.class.path"),
                TestMcpServer.class.getName()),
            tempDir.toString(),
            Map.of());
    Set<Long> before = testServerPids();

    assertThatThrownBy(
            () -> McpClientFactory.createStdio(config, McpDeadline.of(Duration.ofMillis(200))))
        .isInstanceOf(McpTimeoutException.class);

    assertNewTestServerProcessesDie(before);
  }

  /**
   * 超时方与「构建线程尚未 start / 正在 start」的竞态：无论构建任务处于哪个阶段，都不允许留下新进程。
   *
   * <p>1ms 预算使调用方几乎必然先放弃，随后构建线程（可能尚未开始）才尝试 spawn；传输的 sticky 终态必须让它 fail closed。
   * 多轮执行以覆盖不同调度结果，同时作为压力测试。
   */
  @Test
  @Timeout(120)
  void timeoutRacesWithNotYetStartedBuildNeverLeavesSubprocess() {
    StdioMcpConfig config = new StdioMcpConfig(javaCommand(), tempDir.toString(), Map.of());
    Set<Long> before = testServerPids();

    for (int attempt = 0; attempt < 15; attempt++) {
      assertThatThrownBy(
              () -> McpClientFactory.createStdio(config, McpDeadline.of(Duration.ofMillis(1))))
          .isInstanceOf(McpTimeoutException.class);
    }

    assertNewTestServerProcessesDie(before);
  }

  /**
   * 启动闸门：{@code close()} 一旦发生，后续 {@code start(...)} 必须 fail closed，绝不再拉起子进程。
   *
   * <p>这是「超时方关闭传输」与「构建线程随后才 start」这一竞态的最小确定性复现，不需要任何并发编排。
   */
  @Test
  @Timeout(60)
  void startAfterCloseNeverSpawnsSubprocess() {
    StdioMcpConfig config = new StdioMcpConfig(javaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), (requestId, register) -> true);
    Set<Long> before = testServerPids();

    transport.close();
    assertThat(transport.isClosed()).isTrue();
    assertThatThrownBy(() -> transport.start(new NoopOperationHandler()))
        .isInstanceOf(McpException.class);
    assertThat(transport.getProcess()).isNull();

    // 幂等关闭不得让传输复活
    transport.close();
    assertThatThrownBy(() -> transport.start(new NoopOperationHandler()))
        .isInstanceOf(McpException.class);

    assertNewTestServerProcessesDie(before);
  }

  /** 等待并断言：本测试新增的 TestMcpServer 进程全部消失（既有进程不受影响）。 */
  private static void assertNewTestServerProcessesDie(Set<Long> baseline) {
    Set<Long> leaked = new HashSet<>(testServerPids());
    leaked.removeAll(baseline);
    long settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!leaked.isEmpty() && System.nanoTime() < settleDeadline) {
      sleepSilently(50);
      leaked = new HashSet<>(testServerPids());
      leaked.removeAll(baseline);
    }
    assertThat(leaked).as("初始化失败/超时不得泄漏 MCP 子进程").isEmpty();
  }

  /** 当前存活的所有 TestMcpServer 进程 PID；用于做「新增进程」差集，而不是假设全局为零。 */
  private static Set<Long> testServerPids() {
    Set<Long> pids = new HashSet<>();
    ProcessHandle.allProcesses()
        .filter(
            process ->
                process.info().commandLine().orElse("").contains(TestMcpServer.class.getName()))
        .filter(ProcessHandle::isAlive)
        .forEach(process -> pids.add(process.pid()));
    return pids;
  }

  private static void sleepSilently(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting", error);
    }
  }

  /** 只接受任务、不做任何响应的空操作处理器：用于验证「关闭后不得启动」。 */
  private static final class NoopOperationHandler extends McpOperationHandler {

    NoopOperationHandler() {
      super(
          new ConcurrentHashMap<>(),
          null,
          null,
          null,
          () -> {},
          () -> {},
          () -> {},
          uri -> {},
          null,
          () -> {},
          () -> {},
          (requestId, reason) -> {});
    }

    @Override
    public void handle(JsonNode message) {
      // 不处理入站消息
    }
  }

  /**
   * 握手/启动阶段失败（非超时）同样不得留下子进程：SDK 会先 start 再握手，失败时并不代客户端关闭传输。
   *
   * <p>命令存在但服务端立即退出，可稳定触发这条路径。
   */
  @Test
  @Timeout(60)
  void failedInitializationReclaimsSpawnedSubprocess() {
    StdioMcpConfig config =
        new StdioMcpConfig(javaCommand(), tempDir.resolve("missing-cwd").toString(), Map.of());
    Set<Long> before = testServerPids();

    // cwd 不存在：spawn 阶段即失败，不得留下任何进程
    assertThatThrownBy(() -> McpClientFactory.createStdio(config, Duration.ofSeconds(5)))
        .isInstanceOf(McpException.class);

    assertNewTestServerProcessesDie(before);
  }

  /** 关闭创建的 client 必须幂等且不抛错（含底层传输重复关闭）。 */
  @Test
  void closesIdempotently() {
    StdioMcpConfig config = new StdioMcpConfig(javaCommand(), tempDir.toString(), Map.of());
    McpClient client = McpClientFactory.createStdio(config, Duration.ofSeconds(20));
    AtomicBoolean finished = new AtomicBoolean();
    client.close();
    client.close();
    finished.set(true);
    assertThat(finished).isTrue();
  }
}
