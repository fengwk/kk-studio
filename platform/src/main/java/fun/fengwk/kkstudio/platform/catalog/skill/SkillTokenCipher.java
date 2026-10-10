package fun.fengwk.kkstudio.platform.catalog.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import javax.crypto.SecretKey;

import java.util.Objects;

/**
 * Skill Package 私有仓库访问令牌的加密存储边界。
 *
 * <p>直接复用平台既有的 {@link PluginCredentialCodec}（AES-256-GCM）与部署主密钥 {@link
 * PluginCredentialKeyLoader}，不引入任何新的加密实现。 AAD 以固定的 {@code skill-package} 命名空间与 {@code packageName}
 * 作为 region 绑定，因此密文无法在 Package 之间搬运。
 *
 * <p>令牌明文只在临时的加解密调用中出现：绝不持久化为明文、绝不回显、绝不进入日志、Prompt 或模型上下文。
 */
@Component
public class SkillTokenCipher {

  /** 复用 Plugin 凭据 codec 时使用的固定命名空间（AAD 的一部分）。 */
  private static final String NAMESPACE = "skill-package";

  private final PluginCredentialCodec codec;
  private final PluginCredentialKeyLoader keyLoader;

  public SkillTokenCipher(PluginCredentialCodec codec, PluginCredentialKeyLoader keyLoader) {
    this.codec = Objects.requireNonNull(codec, "codec");
    this.keyLoader = Objects.requireNonNull(keyLoader, "keyLoader");
  }

  /** 加密 {@code packageName} 的访问令牌；主密钥不可用时 fail closed，绝不退化为明文或跳过。 */
  public byte[] encrypt(String packageName, String token) {
    Objects.requireNonNull(packageName, "packageName");
    Objects.requireNonNull(token, "token");
    return codec.encrypt(requireKey(), NAMESPACE, packageName, token);
  }

  /** 解密 {@code packageName} 的访问令牌密文；密文被搬移、篡改或主密钥不可用时 fail closed。 */
  public String decrypt(String packageName, byte[] encryptedToken) {
    Objects.requireNonNull(packageName, "packageName");
    Objects.requireNonNull(encryptedToken, "encryptedToken");
    return codec.decrypt(requireKey(), NAMESPACE, packageName, encryptedToken);
  }

  private SecretKey requireKey() {
    try {
      return keyLoader.require();
    } catch (PluginKeyUnavailableException error) {
      // 稳定、无路径的失败原因：凭据能力在本部署不可用，任何明文降级都被禁止。
      throw new IllegalStateException(
          "skill package access token storage is unavailable on this deployment", error);
    }
  }
}
