package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialIntegrityException;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Plugin opaque JSON 的 AES-256-GCM 认证加密包装器。
 *
 * <p>它把共享原语 {@link AesGcmEnvelope} 固定到 Plugin 凭据的唯一当前格式 {@link #FORMAT_VERSION}，AAD 精确绑定 {@code
 * pluginId + region + authGeneration + formatVersion}（长度前缀 UTF-8 编码，避免字段拼接歧义），因此把密文换到另一个
 * pluginId、region 或认证代际上都会认证失败。
 *
 * <p>当前只存在格式 2 一种 Plugin 格式：不读取旧格式，也不接受缺省代际。旧部署的密文必须先经批准完成转换，再进入本版本。原语的 {@link
 * GeneralSecurityException} 在这里收敛为去敏的 {@link PluginCredentialIntegrityException}；密钥/参数错误保持 {@link
 * IllegalArgumentException}。
 */
public final class PluginCredentialCodec {

  /** 当前 Plugin 凭据 envelope 格式版本；envelope 首字节即该值。 */
  public static final int FORMAT_VERSION = 2;

  /** 加密一份 Plugin 秘密载荷，返回完整 envelope。 */
  public byte[] encrypt(
      SecretKey key, String pluginId, UUID authGeneration, String region, String payloadJson) {
    if (payloadJson == null) {
      throw new IllegalArgumentException("payloadJson must not be null");
    }
    return AesGcmEnvelope.encrypt(
        key,
        FORMAT_VERSION,
        aad(pluginId, region, authGeneration),
        payloadJson.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 解密 envelope 并返回 Plugin 秘密载荷原文。
   *
   * <p>格式版本未知、长度不足、tag 校验失败或 AAD 不匹配都抛出 {@link PluginCredentialIntegrityException}，调用方必须据此 fail
   * closed。
   */
  public String decrypt(
      SecretKey key, String pluginId, UUID authGeneration, String region, byte[] envelope) {
    byte[] aad = aad(pluginId, region, authGeneration);
    try {
      return new String(
          AesGcmEnvelope.decrypt(key, FORMAT_VERSION, aad, envelope), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException error) {
      throw new PluginCredentialIntegrityException(
          "plugin credential envelope failed authentication", error);
    }
  }

  private static byte[] aad(String pluginId, String region, UUID authGeneration) {
    if (pluginId == null || region == null || authGeneration == null) {
      throw new IllegalArgumentException(
          "pluginId, region and authGeneration are required for the AAD");
    }
    return LengthPrefixedAad.create()
        .field(pluginId)
        .field(region)
        .field(authGeneration.toString())
        .intValue(FORMAT_VERSION)
        .build();
  }
}
