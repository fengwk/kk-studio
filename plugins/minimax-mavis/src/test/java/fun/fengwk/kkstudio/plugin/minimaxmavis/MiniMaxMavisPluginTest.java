package fun.fengwk.kkstudio.plugin.minimaxmavis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MiniMax Mavis Plugin 安装定义契约测试。
 *
 * <p>锁定 pluginId、名称、版本、固定 CN/EN deep-link region 候选与受控配置 schema，验证 canonical 标识符约束， 并验证官方 loginUrl
 * 的固定构造及未知 region 的防御。
 */
class MiniMaxMavisPluginTest {

  private static final Pattern CANONICAL_PLUGIN_ID = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

  private MiniMaxMavisPlugin plugin;

  @BeforeEach
  void setUp() {
    MavisClient client = new MavisClient(mock(MavisHttpTransport.class));
    MiniMaxMavisAuthHandler authHandler = new MiniMaxMavisAuthHandler(client);
    MiniMaxMavisCredentialRefresher refresher = new MiniMaxMavisCredentialRefresher(client);
    this.plugin = new MiniMaxMavisPlugin(client, authHandler, refresher);
  }

  /** 验证 descriptor 只声明身份：canonical id、展示名与版本号被严格冻结。 */
  @Test
  void descriptorIsFrozenWithCanonicalId() {
    PluginDescriptor descriptor = plugin.descriptor();

    assertEquals("minimax-mavis", descriptor.pluginId());
    assertEquals("MiniMax Mavis", descriptor.name());
    assertEquals("1.0.0", descriptor.version());

    assertTrue(
        descriptor.pluginId().length() <= PluginDescriptor.MAX_PLUGIN_ID_LENGTH,
        "Plugin ID must not exceed max characters");
    assertTrue(
        CANONICAL_PLUGIN_ID.matcher(descriptor.pluginId()).matches(),
        "Plugin ID must match canonical lowercase syntax");
  }

  /** 认证声明是固定 CN/EN deep-link，且与存在的交互式 authHandler 强一致。 */
  @Test
  void authenticationSpecIsFrozenDeepLink() {
    assertEquals(new PluginAuthSpec.DeepLink(List.of("CN", "EN")), plugin.authenticationSpec());
    assertEquals(MiniMaxMavisPlugin.REGIONS, List.of("CN", "EN"));

    // 一致性校验不抛异常即说明 authenticationSpec 与 authHandler 存在性匹配。
    plugin.authenticationSpec().requireConsistentWith(plugin.authHandler());
    assertTrue(plugin.authHandler().isPresent(), "Auth handler must be provided");
    assertTrue(plugin.refresher().isPresent(), "Refresher must be provided");
  }

  /** 唯一受控配置：enabled:boolean 默认 true，普通值与秘密字段分离；不存在未消费的配置项。 */
  @Test
  void configurationSchemaDeclaresEnabledDefaultTrueWithoutSecrets() {
    var schema = plugin.configurationSchema();

    assertEquals(
        Map.of("enabled", new BooleanSchema("whether this Mavis instance contributes its tools")),
        schema.valuesSchema().properties());
    assertEquals(Set.of(), schema.valuesSchema().required());
    assertEquals("{\"enabled\":true}", schema.defaultsJson());
    assertEquals(Map.of(), schema.secrets());
    schema.requireDeclaredSecrets(plugin.authenticationSpec());
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
