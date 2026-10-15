package fun.fengwk.kkstudio.web.upgrade;

import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * 一次性离线升级工具专用的旧 Plugin 凭据密文读取器，只认识 format 1。
 *
 * <p>旧部署的 {@code plugin_credential.encrypted_payload} 是 format 1：{@code [0x01][12 bytes
 * nonce][ciphertext + 128-bit GCM tag]}，AAD 是长度前缀 UTF-8 的 {@code pluginId}、非空 {@code region} 与
 * {@code i32be(1)}。本类只按这一冻结算法读取， 复用平台真实的 {@link AesGcmEnvelope} 与 {@link LengthPrefixedAad}
 * 原语，绝不向生产 {@code fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec} 添加 format 1
 * 双读或格式兜底。
 *
 * <p>它只返回不透明载荷原文：不按 auth fields 过滤、不重建、不解析普通值，也不碰 Skill 令牌密文（那是另一独立命名空间与边界）。任何原始错 key/AAD/tag/未知
 * 格式/截断都收敛为同一个不含底层 cause、消息或载荷片段的 {@link LegacyCredentialUnreadableException}。
 *
 * <p>包内可见（package-private）：它只是离线升级包内部的读取步骤，不是平台 API，也绝不进入常驻运行时代码或对外契约。
 */
final class LegacyPluginCredentialCodec {

  /** 旧 format 1 envelope 的固定前缀开销：1 byte 版本 + 12 bytes nonce + 16 bytes GCM tag。 */
  static final int FORMAT_ONE_OVERHEAD_BYTES =
      1 + AesGcmEnvelope.NONCE_BYTES + AesGcmEnvelope.TAG_BITS / 8;

  /** 旧 Plugin 凭据 envelope 的冻结格式版本；envelope 首字节即该值。 */
  private static final int FORMAT_VERSION = 1;

  private LegacyPluginCredentialCodec() {}

  /**
   * 按旧 format 1 算法解密一份 Plugin 密文，返回不透明载荷原文。
   *
   * @param key 真实平台主密钥（AES-256）
   * @param pluginId 旧行的 pluginId，作为 AAD 的一部分
   * @param region 旧行的非空 region，作为 AAD 的一部分
   * @param envelope 旧 format 1 密文 envelope
   * @throws IllegalArgumentException key 非 AES-256 或 pluginId/region 为空时
   * @throws LegacyCredentialUnreadableException envelope 为 null、格式未知、长度不足、AAD 不匹配、tag 校验失败或跨密钥读取时
   */
  static String decrypt(SecretKey key, String pluginId, String region, byte[] envelope) {
    if (pluginId == null || pluginId.isBlank() || region == null || region.isBlank()) {
      throw new IllegalArgumentException(
          "pluginId and region are required to read a legacy plugin credential");
    }
    // 先构建旧 AAD，再做长度与格式门禁：任何不可信 envelope 都在进入认证解密前被拒绝。
    byte[] aad =
        LengthPrefixedAad.create().field(pluginId).field(region).intValue(FORMAT_VERSION).build();
    if (envelope == null || envelope.length <= FORMAT_ONE_OVERHEAD_BYTES) {
      throw new LegacyCredentialUnreadableException();
    }
    if (envelope[0] != (byte) FORMAT_VERSION) {
      throw new LegacyCredentialUnreadableException();
    }
    try {
      return new String(
          AesGcmEnvelope.decrypt(key, FORMAT_VERSION, aad, envelope), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException error) {
      throw new LegacyCredentialUnreadableException();
    }
  }

  /** 旧 format 1 密文不可读的固定安全异常：绝不携带底层 cause、原始消息或任何明文/密文片段。 */
  static final class LegacyCredentialUnreadableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    LegacyCredentialUnreadableException() {
      super("legacy plugin credential envelope is not readable");
    }
  }
}
