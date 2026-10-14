package fun.fengwk.kkstudio.platform.plugin.service;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 交互式认证 attempt 的一次性 nonce：对外只以高熵句柄（{@code authAttemptId}）出现，服务端只保存它的 SHA-256 摘要。
 *
 * <p>{@link #issue()} 每次产生 256 位随机句柄，{@link #hash(String)} 用领域分隔前缀派生摘要，因此 pending attempt
 * 表即便泄露也无法反推出可用句柄， 更无法伪造或重放一个未被本部署签发过的 attempt。句柄与摘要都不进入日志、普通配置或错误消息。
 */
public final class PluginAuthNonce {

  /** 一次性 nonce 的原始熵（字节）。 */
  public static final int NONCE_BYTES = 32;

  /** 摘要的领域分隔前缀：让同一份随机量在不同用途下不可能得到相同摘要。 */
  private static final String HASH_DOMAIN = "kk-studio:plugin-auth-attempt:";

  private static final SecureRandom RANDOM = new SecureRandom();

  private PluginAuthNonce() {}

  /** 产生一个新的高熵 attempt 句柄（base64url 无填充，43 字符）。 */
  public static String issue() {
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
  }

  /** 句柄的 64 位小写十六进制 SHA-256 摘要；空白或 null 句柄被拒绝。 */
  public static String hash(String authAttemptId) {
    Objects.requireNonNull(authAttemptId, "authAttemptId");
    if (authAttemptId.isBlank() || !authAttemptId.equals(authAttemptId.strip())) {
      throw new IllegalArgumentException("authAttemptId must be a non-blank handle");
    }
    // 领域分隔前缀与句柄一起进入同一份字节流，复用目录指纹的 SHA-256 原语。
    return PluginCatalogFingerprint.sha256Hex(
        (HASH_DOMAIN + authAttemptId).getBytes(StandardCharsets.UTF_8));
  }
}
