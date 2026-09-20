package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthRejectedException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginRenewalNotSentException;

import java.time.Clock;
import java.util.Objects;

/**
 * MiniMax Mavis 的凭据自动刷新。
 *
 * <p>异常语义严格对齐 {@link PluginCredentialRefresher}：
 *
 * <ul>
 *   <li>确定性认证拒绝（HTTP 401 / 业务认证码 / 返回 token 已不可用）→ {@link PluginAuthRejectedException}，收敛为 {@code
 *       REAUTH_REQUIRED}；
 *   <li>本地签名或编码失败（发生在任何网络调用之前）→ {@link PluginRenewalNotSentException}，允许有界延迟重试；
 *   <li>传输失败、超时、响应无法解析 → 普通 {@link PluginCredentialException}，因为服务端可能已经签发新 token，永不重放。
 * </ul>
 */
public final class MiniMaxMavisCredentialRefresher implements PluginCredentialRefresher {

  private final MavisClient client;
  private final Clock clock;

  public MiniMaxMavisCredentialRefresher(MavisClient client) {
    this(client, Clock.systemDefaultZone());
  }

  MiniMaxMavisCredentialRefresher(MavisClient client, Clock clock) {
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public PluginCredentialMaterial refresh(PluginCredentialSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    PluginCredentialMaterial candidate = renew(snapshot);
    if (!candidate.region().equals(snapshot.region())) {
      throw new PluginCredentialException("MiniMax Mavis renewal changed the credential region");
    }
    return candidate;
  }

  private PluginCredentialMaterial renew(PluginCredentialSnapshot snapshot) {
    MiniMaxMavisCredentialPayload payload =
        MiniMaxMavisCredentialPayload.parse(snapshot.payloadJson());
    MavisRegion region;
    try {
      region = MavisRegion.parse(snapshot.region());
    } catch (MavisException error) {
      throw new PluginCredentialException("MiniMax Mavis credential region is not recognised");
    }
    MavisCredential current =
        new MavisCredential(
            payload.accessToken(), region, snapshot.expiresAt(), payload.clientUuid());
    MavisCredential renewed;
    try {
      renewed = client.renew(current);
    } catch (MavisAuthException rejected) {
      throw new PluginAuthRejectedException(rejected.getMessage(), rejected);
    } catch (MavisValidationException notSent) {
      // renewal 请求的本地签名与编码发生在任何传输调用之前，因此这是可以证明「未发出」的唯一失败形态。
      throw new PluginRenewalNotSentException(notSent.getMessage(), notSent);
    } catch (MavisException unknown) {
      throw new PluginCredentialException(
          "MiniMax Mavis renewal outcome is unknown: " + unknown.getMessage(), unknown);
    }
    return MiniMaxMavisCredentials.toMaterial(renewed, clock.instant());
  }
}
