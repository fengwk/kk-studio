package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;

import java.time.Instant;

/** MiniMax Mavis 凭据材料与协议凭据之间的唯一转换点。 */
final class MiniMaxMavisCredentials {

  private MiniMaxMavisCredentials() {}

  /** 把一次成功的登录或 renewal 结果打包成待加密材料。 */
  static PluginCredentialMaterial toMaterial(MavisCredential credential, Instant obtainedAt) {
    return new PluginCredentialMaterial(
        MiniMaxMavisAuthHandler.regionId(credential.region()),
        credential.expiresAt(),
        MiniMaxMavisRefreshSchedule.nextRefreshAt(obtainedAt, credential.expiresAt()),
        MiniMaxMavisCredentialPayload.of(credential, obtainedAt).toJson());
  }
}
