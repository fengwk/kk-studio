package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * MiniMax Mavis 的 Studio Plugin 安装期定义：安装身份、受控配置、固定 CN/EN region 候选、deep-link 认证与凭据刷新。
 *
 * <p>它只声明安装期事实，不持有 repository、密文或主密钥：认证回调与刷新结果都以 opaque JSON 材料交回 Platform，由 Platform 的凭据 store
 * 加密落库。 运行期实例（15 个模型工具与其执行器）由 {@link #configure(PluginConfig, PluginContext)} 按一次配置装配，归本 Plugin
 * 所有并可关闭。
 */
public final class MiniMaxMavisPlugin implements StudioPlugin {

  /** 全局唯一安装身份。 */
  public static final String PLUGIN_ID = "minimax-mavis";

  public static final String PLUGIN_NAME = "MiniMax Mavis";

  public static final String PLUGIN_VERSION = "1.0.0";

  /** 固定 region 候选：deep-link 认证声明的顺序就是管理面与 prepare 允许的顺序。 */
  public static final List<String> REGIONS =
      List.copyOf(
          Arrays.stream(MavisRegion.values()).map(MiniMaxMavisAuthHandler::regionId).toList());

  /** 唯一安装身份，同时是 Contributor descriptor 的事实源。 */
  static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(PLUGIN_ID, PLUGIN_NAME, PLUGIN_VERSION);

  private static final PluginConfigSchema CONFIGURATION_SCHEMA =
      new PluginConfigSchema(
          new InputSchema(
              "MiniMax Mavis configuration",
              Map.of(
                  "enabled",
                  new BooleanSchema("whether this Mavis instance contributes its tools")),
              Set.of(),
              false),
          "{\"enabled\":true}",
          Map.of());

  private static final PluginAuthSpec AUTHENTICATION_SPEC = new PluginAuthSpec.DeepLink(REGIONS);

  private final MavisClient client;
  private final MiniMaxMavisAuthHandler authHandler;
  private final MiniMaxMavisCredentialRefresher refresher;

  public MiniMaxMavisPlugin(
      MavisClient client,
      MiniMaxMavisAuthHandler authHandler,
      MiniMaxMavisCredentialRefresher refresher) {
    this.client = Objects.requireNonNull(client, "client");
    this.authHandler = Objects.requireNonNull(authHandler, "authHandler");
    this.refresher = Objects.requireNonNull(refresher, "refresher");
  }

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public PluginConfigSchema configurationSchema() {
    return CONFIGURATION_SCHEMA;
  }

  @Override
  public PluginAuthSpec authenticationSpec() {
    return AUTHENTICATION_SPEC;
  }

  /**
   * 按一次普通配置装配实例：只按 schema 严格解析 {@code enabled}，不联网、不解析凭据。
   *
   * <p>启用时由实例拥有自己的工具执行器、capability 缓存与资源访问；停用时两域都没有新贡献。配置文本非法（未知字段、类型不符、超限等）时抛出 {@link
   * IllegalArgumentException}，由宿主在冻结 catalog 前收敛。
   */
  @Override
  public PluginInstance configure(PluginConfig config, PluginContext context) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(context, "context");
    String resolved = CONFIGURATION_SCHEMA.resolveValues(config.valuesJson());
    boolean enabled = JsonValues.readTree(resolved).get("enabled").asBoolean();
    return enabled
        ? MiniMaxMavisInstance.enabled(client, context)
        : MiniMaxMavisInstance.disabled();
  }

  @Override
  public Optional<PluginAuthHandler> authHandler() {
    return Optional.of(authHandler);
  }

  @Override
  public Optional<PluginCredentialRefresher> refresher() {
    return Optional.of(refresher);
  }
}
