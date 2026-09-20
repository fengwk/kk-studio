package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次已认证的 Mavis access token 及其路由元数据。
 *
 * <p>{@code expiresAt} 来自 JWT 数值 {@code exp}，是本地时限而非身份事实；{@code clientUuid} 是首次登录生成并随凭据持久化的稳定
 * client UUID，renewal 请求必须携带它。凭据本身不负责持久化与加密，持久化由 Platform 的 credential store 承担。
 */
public record MavisCredential(
    String token, MavisRegion region, Instant expiresAt, String clientUuid) {

  /** 本地视为即将过期的安全余量：剩余不足该时长的 token 在发出请求前就被拒绝。 */
  public static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

  public MavisCredential {
    if (token == null || token.isBlank()) {
      throw new MavisValidationException("MiniMax Mavis access token must not be empty");
    }
    if (clientUuid == null || clientUuid.isBlank()) {
      throw new MavisValidationException("MiniMax Mavis client UUID must not be empty");
    }
    region = Objects.requireNonNull(region, "region");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /**
   * 校验 JWT 数值 {@code exp} 后构造凭据；任何缺失、非数值或即将过期的 token 都失败。
   *
   * <p>登录回调 token 与 renewal 返回的新 token 都只经本方法进入系统，因此「本地时限」规则只有一处实现。
   */
  public static MavisCredential fromJwt(
      String token, MavisRegion region, Instant now, String clientUuid) {
    String normalized = token == null ? "" : token.strip();
    if (normalized.isEmpty()) {
      throw new MavisAuthException("MiniMax Mavis access token must not be empty");
    }
    Instant expiresAt =
        MavisJwt.numericExpiresAt(normalized)
            .orElseThrow(
                () ->
                    new MavisAuthException(
                        "MiniMax Mavis access token is not a JWT with a numeric exp"));
    if (!expiresAt.isAfter(now.plus(EXPIRY_SKEW))) {
      throw new MavisAuthException(
          "MiniMax Mavis access token is expired; re-authenticate the plugin");
    }
    return new MavisCredential(normalized, region, expiresAt, clientUuid);
  }

  @Override
  public String toString() {
    return "MavisCredential[region="
        + region.id()
        + ", expiresAt="
        + expiresAt
        + ", clientUuid="
        + clientUuid
        + ", token="
        + MavisRedaction.REDACTED
        + "]";
  }
}
