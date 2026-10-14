package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialIntegrityException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Plugin opaque JSON 的 AES-256-GCM 认证加密编解码器。
 *
 * <p>envelope 是固定二进制格式：1 byte 格式版本、12 bytes 随机 nonce、其余为 ciphertext 与 128-bit GCM tag。AAD 精确绑定
 * {@code pluginId + region + formatVersion}（长度前缀 UTF-8 编码，避免字段拼接歧义），因此把密文换到另一个 pluginId 或 region
 * 上都会认证失败。
 *
 * <p>每次写入都使用新的 96-bit 随机 nonce；版本、nonce 与 tag 都在密文内，调用方不能选择、覆盖或省略其中任何一个。
 */
public final class PluginCredentialCodec {

  /** 当前 envelope 格式版本；envelope 首字节即该值。 */
  public static final int FORMAT_VERSION = 1;

  /** 单次写入使用的 GCM nonce 字节数。 */
  public static final int NONCE_BYTES = 12;

  /** GCM 认证 tag 位数。 */
  public static final int TAG_BITS = 128;

  /** 明文与密文的最大长度差（nonce + version + tag）。 */
  private static final int OVERHEAD_BYTES = 1 + NONCE_BYTES + TAG_BITS / 8;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private static final SecureRandom NONCE_SOURCE = new SecureRandom();

  /** 加密一份 Plugin 秘密载荷，返回完整 envelope。 */
  public byte[] encrypt(SecretKey key, String pluginId, String region, String payloadJson) {
    requireKey(key);
    if (payloadJson == null) {
      throw new IllegalArgumentException("payloadJson must not be null");
    }
    byte[] nonce = new byte[NONCE_BYTES];
    NONCE_SOURCE.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(pluginId, region));
      byte[] sealed = cipher.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
      byte[] envelope = new byte[1 + NONCE_BYTES + sealed.length];
      envelope[0] = (byte) FORMAT_VERSION;
      System.arraycopy(nonce, 0, envelope, 1, NONCE_BYTES);
      System.arraycopy(sealed, 0, envelope, 1 + NONCE_BYTES, sealed.length);
      return envelope;
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("cannot encrypt plugin credential payload", error);
    }
  }

  /**
   * 解密 envelope 并返回 Plugin 秘密载荷原文。
   *
   * <p>格式版本未知、长度不足、tag 校验失败或 AAD 不匹配都抛出 {@link PluginCredentialIntegrityException}，调用方必须据此 fail
   * closed。
   */
  public String decrypt(SecretKey key, String pluginId, String region, byte[] envelope) {
    requireKey(key);
    if (envelope == null || envelope.length <= OVERHEAD_BYTES) {
      throw new PluginCredentialIntegrityException("plugin credential envelope is truncated");
    }
    if (envelope[0] != (byte) FORMAT_VERSION) {
      throw new PluginCredentialIntegrityException(
          "unsupported plugin credential envelope format version");
    }
    byte[] nonce = Arrays.copyOfRange(envelope, 1, 1 + NONCE_BYTES);
    byte[] sealed = Arrays.copyOfRange(envelope, 1 + NONCE_BYTES, envelope.length);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(pluginId, region));
      return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    } catch (AEADBadTagException error) {
      throw new PluginCredentialIntegrityException(
          "plugin credential envelope failed authentication", error);
    } catch (GeneralSecurityException error) {
      throw new PluginCredentialIntegrityException(
          "plugin credential envelope cannot be decrypted", error);
    }
  }

  private static void requireKey(SecretKey key) {
    if (key == null || !"AES".equalsIgnoreCase(key.getAlgorithm())) {
      throw new IllegalArgumentException("plugin credential key must be an AES key");
    }
    byte[] raw = key.getEncoded();
    if (raw == null || raw.length != PluginCredentialKeyLoader.KEY_BYTES) {
      throw new IllegalArgumentException(
          "plugin credential key must be exactly "
              + PluginCredentialKeyLoader.KEY_BYTES
              + " bytes");
    }
  }

  /**
   * AAD 的规范编码：逐字段写入 4 bytes 大端长度前缀与 UTF-8 字节，最后写入格式版本。
   *
   * <p>长度前缀使不同字段拼接不会产生歧义，因此密文无法在 pluginId / region / 版本之间偷换。
   */
  private static byte[] aad(String pluginId, String region) {
    if (pluginId == null || region == null) {
      throw new IllegalArgumentException("pluginId and region are required for the AAD");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      writeField(out, pluginId);
      writeField(out, region);
      writeInt(out, FORMAT_VERSION);
    } catch (IOException error) {
      throw new IllegalStateException("cannot build plugin credential AAD", error);
    }
    return out.toByteArray();
  }

  private static void writeField(ByteArrayOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeInt(out, bytes.length);
    out.write(bytes);
  }

  private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }
}
