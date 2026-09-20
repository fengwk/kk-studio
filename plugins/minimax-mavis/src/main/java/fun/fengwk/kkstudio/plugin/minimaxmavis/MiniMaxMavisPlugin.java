package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MiniMax Mavis 的 Studio Plugin 描述：安装身份、固定 CN/EN region 候选、deep-link 认证与凭据刷新。
 *
 * <p>它只声明身份与两个能力入口，不持有 repository、密文或主密钥：认证回调与刷新结果都以 opaque JSON 材料交回 Platform， 由 Platform 的凭据
 * store 加密落库。
 */
public final class MiniMaxMavisPlugin implements StudioPlugin {

  /** 全局唯一安装身份。 */
  public static final String PLUGIN_ID = "minimax-mavis";

  public static final String PLUGIN_NAME = "MiniMax Mavis";

  public static final String PLUGIN_VERSION = "1.0.0";

  /** 固定 region 候选：descriptor 声明的顺序就是管理面与 prepare 允许的顺序。 */
  public static final List<String> REGIONS =
      List.copyOf(
          Arrays.stream(MavisRegion.values()).map(MiniMaxMavisAuthHandler::regionId).toList());

  private static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(PLUGIN_ID, PLUGIN_NAME, PLUGIN_VERSION, REGIONS);

  private final MiniMaxMavisAuthHandler authHandler;
  private final MiniMaxMavisCredentialRefresher refresher;

  public MiniMaxMavisPlugin(
      MiniMaxMavisAuthHandler authHandler, MiniMaxMavisCredentialRefresher refresher) {
    this.authHandler = Objects.requireNonNull(authHandler, "authHandler");
    this.refresher = Objects.requireNonNull(refresher, "refresher");
  }

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
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
