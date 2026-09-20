package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * MiniMax Mavis Plugin 描述符与官方登录入口契约测试。
 *
 * <p>锁定 pluginId、名称、版本与固定 CN/EN region 候选，验证 canonical 标识符约束， 并验证官方 loginUrl 的固定构造及未知 region 的防御。
 */
class MiniMaxMavisPluginTest {

  private static final Pattern CANONICAL_PLUGIN_ID = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  private MiniMaxMavisPlugin plugin;

  @BeforeEach
  void setUp() {
    MavisClient client = new MavisClient(mock(MavisHttpTransport.class));
    MiniMaxMavisAuthHandler authHandler = new MiniMaxMavisAuthHandler(client);
    MiniMaxMavisCredentialRefresher refresher = new MiniMaxMavisCredentialRefresher(client);
    this.plugin = new MiniMaxMavisPlugin(authHandler, refresher);
  }

  /** 验证 descriptor 身份、展示名、版本号与支持的 region 列表被严格冻结。 */
  @Test
  void descriptorIsFrozenWithCanonicalIdAndOfficialRegions() {
    PluginDescriptor descriptor = plugin.descriptor();

    assertEquals("minimax-mavis", descriptor.pluginId());
    assertEquals("MiniMax Mavis", descriptor.name());
    assertEquals("1.0.0", descriptor.version());
    assertEquals(List.of("CN", "EN"), descriptor.regions());

    assertTrue(
        descriptor.pluginId().length() <= PluginDescriptor.MAX_PLUGIN_ID_LENGTH,
        "Plugin ID must not exceed max characters");
    assertTrue(
        CANONICAL_PLUGIN_ID.matcher(descriptor.pluginId()).matches(),
        "Plugin ID must match canonical lowercase syntax");
    assertTrue(descriptor.supportsAuthentication(), "Plugin must declare authentication support");
    assertTrue(descriptor.acceptsRegion("CN"), "Plugin descriptor must accept CN");
    assertTrue(descriptor.acceptsRegion("EN"), "Plugin descriptor must accept EN");
  }

  /** 验证 SPI 入口均提供非空实现。 */
  @Test
  void exposesAuthHandlerAndRefresher() {
    assertTrue(plugin.authHandler().isPresent(), "Auth handler must be provided");
    assertTrue(plugin.refresher().isPresent(), "Refresher must be provided");
  }

  /** 验证 CN 与 EN 返回固定的官方登录重定向地址。 */
  @Test
  void loginUrlReturnsFixedOfficialOrigins() {
    var authHandler = plugin.authHandler().orElseThrow();

    assertEquals(
        "https://agent.minimaxi.com/login?sso=1&download_source=default",
        authHandler.loginUrl("CN"),
        "CN login URL must point to agent.minimaxi.com with official login path");

    assertEquals(
        "https://agent.minimax.io/login?sso=1&download_source=default",
        authHandler.loginUrl("EN"),
        "EN login URL must point to agent.minimax.io with official login path");
  }

  /** 未知或非法 region 必须抛出异常。 */
  @ParameterizedTest
  @ValueSource(strings = {"US", "JP", "unknown", "", " "})
  void loginUrlRejectsUnknownOrNonCanonicalRegions(String invalidRegion) {
    var authHandler = plugin.authHandler().orElseThrow();
    assertThrows(
        MavisValidationException.class,
        () -> authHandler.loginUrl(invalidRegion),
        () -> "loginUrl must reject unrecognized region: " + invalidRegion);
  }

  /** null region 同样必须抛出异常。 */
  @Test
  void loginUrlRejectsNullRegion() {
    var authHandler = plugin.authHandler().orElseThrow();
    assertThrows(MavisValidationException.class, () -> authHandler.loginUrl(null));
  }
}
