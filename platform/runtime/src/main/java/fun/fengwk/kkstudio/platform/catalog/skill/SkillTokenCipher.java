package fun.fengwk.kkstudio.platform.catalog.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Skill Package 私有仓库访问令牌的加密存储边界。
 *
 * <p>它只依赖 JDK-only 的 {@link AesGcmEnvelope} 原语与部署主密钥 {@link
 * PluginCredentialKeyLoader}，不引入任何新的加密实现。AAD 以固定的 {@code skill-package} 命名空间与 {@code packageName}
 * 作为绑定，因此密文无法在 Package 之间搬运。
 *
 * <p>Skill 令牌没有 Plugin 凭据的认证代际：它与 Plugin 凭据格式分离，保持既有格式 {@link #FORMAT_VERSION} 与既有 AAD 布局，从而继续读取
 * 历史密文。原语的 {@link GeneralSecurityException} 在这里收敛为去敏的失败关闭错误；主密钥不可用同样 fail closed。
 *
 * <p>令牌明文只在临时的加解密调用中出现：绝不持久化为明文、绝不回显、绝不进入日志、Prompt 或模型上下文。
 */
@Component
public class SkillTokenCipher {

  /** 复用 AAD 与 envelope 时使用的固定命名空间。 */
  private static final String NAMESPACE = "skill-package";

  /** Skill 令牌 envelope 的既有格式版本；与 Plugin 凭据的格式 2 分离，独立演进。 */
  private static final int FORMAT_VERSION = 1;

  private final PluginCredentialKeyLoader keyLoader;

  public SkillTokenCipher(PluginCredentialKeyLoader keyLoader) {
    this.keyLoader = Objects.requireNonNull(keyLoader, "keyLoader");
  }

  /** 加密 {@code packageName} 的访问令牌；主密钥不可用时 fail closed，绝不退化为明文或跳过。 */
  public byte[] encrypt(String packageName, String token) {
    Objects.requireNonNull(packageName, "packageName");
    Objects.requireNonNull(token, "token");
    return AesGcmEnvelope.encrypt(
        requireKey(), FORMAT_VERSION, aad(packageName), token.getBytes(StandardCharsets.UTF_8));
  }

  /** 解密 {@code packageName} 的访问令牌密文；密文被搬移、篡改或主密钥不可用时 fail closed。 */
  public String decrypt(String packageName, byte[] encryptedToken) {
    Objects.requireNonNull(packageName, "packageName");
    Objects.requireNonNull(encryptedToken, "encryptedToken");
    try {
      return new String(
          AesGcmEnvelope.decrypt(requireKey(), FORMAT_VERSION, aad(packageName), encryptedToken),
          StandardCharsets.UTF_8);
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("skill package access token cannot be authenticated", error);
    }
  }

  private static byte[] aad(String packageName) {
    return LengthPrefixedAad.create()
        .field(NAMESPACE)
        .field(packageName)
        .intValue(FORMAT_VERSION)
        .build();
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
