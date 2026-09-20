package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/**
 * MiniMax Mavis 的 {@code DEEP_LINK} 认证交互。
 *
 * <p>{@code loginUrl} 只按 region 返回固定官方 origin 上的 SSO 链接，不接受自定义 base URL；{@code complete}
 * 复用协议客户端的严格回调校验 （长度、字段数、authority、region、token 时效）与只读 capability catalog 在线验证，然后把 token、稳定 client
 * UUID 与取得时间打包成 opaque JSON 交给 Platform 加密。
 */
public final class MiniMaxMavisAuthHandler implements PluginAuthHandler {

  private final MavisClient client;
  private final Clock clock;

  public MiniMaxMavisAuthHandler(MavisClient client) {
    this(client, Clock.systemDefaultZone());
  }

  MiniMaxMavisAuthHandler(MavisClient client, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public String loginUrl(String region) {
    return MavisRegion.parse(region).loginUrl();
  }

  @Override
  public PluginCredentialMaterial complete(String callbackUrl) {
    MavisCredential credential = client.completeLogin(callbackUrl);
    return MiniMaxMavisCredentials.toMaterial(credential, clock.instant());
  }

  /** descriptor 暴露给管理面的 region 候选；与 {@link MavisRegion} 只有一处映射。 */
  static String regionId(MavisRegion region) {
    return region.id().toUpperCase(Locale.ROOT);
  }
}
