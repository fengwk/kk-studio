package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiniMax Mavis 运行期实例契约测试：唯一注册点、enabled 真实消费与实例资源所有权。
 *
 * <p>验证 {@code configure} 默认装配完整 15 工具、停用时两域都没有贡献、严格按 schema 拒绝非法配置；验证 15 工具不是容器 bean 而是实例私有，
 * 且装配与关闭都不联网、不读取凭据、不关闭 Spring 注入的 client；验证装配失败（含进程级 Error）时立即释放已经创建的工具执行器并原样传播。
 */
class MiniMaxMavisInstanceTest {

  private MavisClient client;
  private MiniMaxMavisPlugin plugin;

  @BeforeEach
  void setUp() {
    // client 是宿主注入的共享对象：任何调用都说明插件在装配/关闭期触达了外部或共享资源。
    this.client = mock(MavisClient.class);
    this.plugin =
        new MiniMaxMavisPlugin(
            client,
            new MiniMaxMavisAuthHandler(client),
            new MiniMaxMavisCredentialRefresher(client));
  }

  /** 默认配置启用实例：唯一注册点提供完整 15 工具，Canvas 域为空，两域列表冻结。 */
  @Test
  void defaultConfigureProvidesFifteenToolsAndEmptyCanvas() {
    CountingCredentialAccess credentialAccess = new CountingCredentialAccess();
    PluginContext context =
        new PluginContext(credentialAccess, Optional.empty(), Optional.empty(), Optional.empty());

    PluginInstance instance =
        plugin.configure(PluginConfig.initial(plugin.configurationSchema()), context);

    assertEquals(
        1, instance.harnessContributors().size(), "enabled instance contributes one contributor");
    assertTrue(instance.canvasAdapters().isEmpty(), "MiniMax Mavis has no Canvas contribution");
    MiniMaxMavisHarnessContributor contributor =
        (MiniMaxMavisHarnessContributor) instance.harnessContributors().get(0);
    assertEquals(15, contributor.toolCount(), "must expose all 15 tools");
    assertEquals(
        plugin.descriptor().version(),
        contributor.descriptor().version(),
        "contributor version must derive from the same plugin descriptor");

    // 两域列表冻结，调用方无法修改；实例关闭后仍可安全读取。
    assertThrows(
        UnsupportedOperationException.class, () -> instance.harnessContributors().add(null));
    assertThrows(UnsupportedOperationException.class, () -> instance.canvasAdapters().add(null));

    // 装配期不联网、不读取凭据；宿主注入的 client 完全未被触达。
    assertEquals(0, credentialAccess.resolveCount(), "configure must not resolve credentials");
    verifyNoInteractions(client);

    instance.close();
    instance.close();
    assertEquals(
        1, instance.harnessContributors().size(), "close must not mutate the frozen lists");
  }

  /** 成功装配后关闭实例：只关闭实例自己的真实执行器，第二次 close 不再产生任何变更。 */
  @Test
  void closeShutsDownTheInstanceExecutorExactlyOnce() {
    CountingCredentialAccess credentialAccess = new CountingCredentialAccess();
    PluginContext context =
        new PluginContext(credentialAccess, Optional.empty(), Optional.empty(), Optional.empty());
    ArgumentCaptor<MiniMaxMavisHarnessContributor.ToolDependencies> captured =
        ArgumentCaptor.forClass(MiniMaxMavisHarnessContributor.ToolDependencies.class);

    PluginInstance instance;
    try (MockedStatic<MiniMaxMavisHarnessContributor> mocked =
        mockStatic(MiniMaxMavisHarnessContributor.class, CALLS_REAL_METHODS)) {
      // 调用真实 create，因此捕获到的是实例真正持有的执行器。
      instance = plugin.configure(PluginConfig.initial(plugin.configurationSchema()), context);
      mocked.verify(() -> MiniMaxMavisHarnessContributor.create(captured.capture()));
    }

    ExecutorService executor = captured.getValue().executor();
    assertFalse(executor.isShutdown(), "the instance executor must be live while the instance is");
    assertEquals(
        15, ((MiniMaxMavisHarnessContributor) instance.harnessContributors().get(0)).toolCount());

    instance.close();
    assertTrue(executor.isShutdown(), "close must shut down the executor owned by the instance");

    instance.close();
    assertTrue(executor.isShutdown(), "a second close must be a no-op for the same executor");
    assertEquals(
        1, instance.harnessContributors().size(), "close must not mutate the frozen lists");

    assertEquals(0, credentialAccess.resolveCount(), "configure must not resolve credentials");
    verifyNoInteractions(client);
  }

  /** 显式停用实例：两域都没有新贡献，装配期同样不联网、不读取凭据。 */
  @Test
  void disabledConfigureContributesNothing() {
    CountingCredentialAccess credentialAccess = new CountingCredentialAccess();
    PluginContext context =
        new PluginContext(credentialAccess, Optional.empty(), Optional.empty(), Optional.empty());

    PluginInstance instance = plugin.configure(new PluginConfig(3, "{\"enabled\":false}"), context);

    assertTrue(instance.harnessContributors().isEmpty(), "disabled instance contributes nothing");
    assertTrue(instance.canvasAdapters().isEmpty(), "disabled instance has no Canvas contribution");
    assertEquals(
        0, credentialAccess.resolveCount(), "disabled configure must not resolve credentials");
    verifyNoInteractions(client);

    instance.close();
    instance.close();
  }

  /** 配置严格按 schema 解析：未知字段与非布尔 enabled 都被拒绝，绝不静默兜底。 */
  @Test
  void configureRejectsConfigThatViolatesTheSchema() {
    PluginContext context =
        new PluginContext(
            new CountingCredentialAccess(), Optional.empty(), Optional.empty(), Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> plugin.configure(new PluginConfig(1, "{\"unknown\":true}"), context));
    assertThrows(
        IllegalArgumentException.class,
        () -> plugin.configure(new PluginConfig(1, "{\"enabled\":\"true\"}"), context));
  }

  /** 装配抛出 RuntimeException 时必须立即释放已创建的执行器，绝不泄漏给宿主。 */
  @Test
  void runtimeFailureReleasesTheAlreadyCreatedExecutor() {
    PluginContext context =
        new PluginContext(
            new CountingCredentialAccess(), Optional.empty(), Optional.empty(), Optional.empty());
    ArgumentCaptor<MiniMaxMavisHarnessContributor.ToolDependencies> captured = capture();

    try (MockedStatic<MiniMaxMavisHarnessContributor> mocked =
        mockStatic(MiniMaxMavisHarnessContributor.class)) {
      mocked
          .when(() -> MiniMaxMavisHarnessContributor.create(captured.capture()))
          .thenThrow(new IllegalStateException("tool schema rejected"));

      assertThrows(
          IllegalStateException.class,
          () -> plugin.configure(PluginConfig.initial(plugin.configurationSchema()), context));
    }

    assertTrue(
        captured.getValue().executor().isShutdown(),
        "a failed configure must shut down the executor it already created");
    verifyNoInteractions(client);
  }

  /** 装配抛出进程级 Error：executor 同样被释放，且原始 Error 原样向上传播。 */
  @Test
  void errorIsPropagatedVerbatimAfterReleasingTheExecutor() {
    PluginContext context =
        new PluginContext(
            new CountingCredentialAccess(), Optional.empty(), Optional.empty(), Optional.empty());
    ArgumentCaptor<MiniMaxMavisHarnessContributor.ToolDependencies> captured = capture();
    AssertionError primary = new AssertionError("tool schema rejected");

    try (MockedStatic<MiniMaxMavisHarnessContributor> mocked =
        mockStatic(MiniMaxMavisHarnessContributor.class)) {
      mocked
          .when(() -> MiniMaxMavisHarnessContributor.create(captured.capture()))
          .thenThrow(primary);

      AssertionError thrown =
          assertThrows(
              AssertionError.class,
              () -> plugin.configure(PluginConfig.initial(plugin.configurationSchema()), context));
      assertEquals(primary, thrown, "the original Error must propagate unchanged");
    }

    assertTrue(
        captured.getValue().executor().isShutdown(),
        "a failed configure must shut down the executor it already created");
    verifyNoInteractions(client);
  }

  private static ArgumentCaptor<MiniMaxMavisHarnessContributor.ToolDependencies> capture() {
    return ArgumentCaptor.forClass(MiniMaxMavisHarnessContributor.ToolDependencies.class);
  }

  /** 计数凭据解析次数的假 scoped 凭据访问：装配期任何解析都说明发生了未预期的凭据读取。 */
  private static final class CountingCredentialAccess implements PluginCredentialAccess {
    private final AtomicInteger resolves = new AtomicInteger();

    @Override
    public PluginCredentialSnapshot resolve() {
      resolves.incrementAndGet();
      throw new IllegalStateException("configure must not resolve credentials");
    }

    @Override
    public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
      return false;
    }

    int resolveCount() {
      return resolves.get();
    }
  }
}
