package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiniMax Mavis 运行期实例契约测试：唯一注册点与 enabled 真实消费。
 *
 * <p>验证 {@code configure} 默认装配完整 15 工具、停用时两域都没有贡献、严格按 schema 拒绝非法配置；验证 15 工具不是容器 bean 而是实例私有，
 * 且装配与关闭都不联网、不读取凭据、不关闭 Spring 注入的 client；实例不持有需要关闭的资源。
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
