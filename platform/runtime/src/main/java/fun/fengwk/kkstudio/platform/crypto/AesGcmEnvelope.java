package fun.fengwk.kkstudio.platform.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * JDK-only 的 AES-256-GCM 二进制 envelope 原语，供多个凭据消费者共享。
 *
 * <p>envelope 是固定二进制格式：1 byte 格式版本、12 bytes 随机 nonce、其余为 ciphertext 与 128-bit GCM tag。格式版本由调用方显式给出，
 * AAD 也由调用方显式构造：本原语不内置任何身份字段，因此不同消费方可以各自绑定自己的命名空间而不共享语义。
 *
 * <p>{@link #decrypt} 以 {@link GeneralSecurityException}
 * 表达「格式未知、长度不足或认证失败」这类不可信密文；加密侧的密钥/参数错误是编程错误， 以 {@link IllegalArgumentException} 表达。每次加密都使用新的
 * 96-bit 随机 nonce。
 */
public final class AesGcmEnvelope {

  /** 单次写入使用的 GCM nonce 字节数。 */
  public static final int NONCE_BYTES = 12;

  /** GCM 认证 tag 位数。 */
  public static final int TAG_BITS = 128;

  /** AES-256 密钥的精确字节数。 */
  public static final int KEY_BYTES = 32;

  /** 明文与密文的最大长度差（nonce + version + tag）。 */
  private static final int OVERHEAD_BYTES = 1 + NONCE_BYTES + TAG_BITS / 8;

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private static final SecureRandom NONCE_SOURCE = new SecureRandom();

  private AesGcmEnvelope() {}

  /** 以给定格式版本与 AAD 加密明文，返回完整 envelope。 */
  public static byte[] encrypt(SecretKey key, int formatVersion, byte[] aad, byte[] plaintext) {
    requireAes256Key(key);
    requireFormatVersion(formatVersion);
    Objects.requireNonNull(aad, "aad");
    Objects.requireNonNull(plaintext, "plaintext");
    byte[] nonce = new byte[NONCE_BYTES];
    NONCE_SOURCE.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad);
      byte[] sealed = cipher.doFinal(plaintext);
      byte[] envelope = new byte[1 + NONCE_BYTES + sealed.length];
      envelope[0] = (byte) formatVersion;
      System.arraycopy(nonce, 0, envelope, 1, NONCE_BYTES);
      System.arraycopy(sealed, 0, envelope, 1 + NONCE_BYTES, sealed.length);
      return envelope;
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("cannot encrypt the credential envelope", error);
    }
  }

  /**
   * 解密 envelope 并返回明文。
   *
   * <p>格式版本未知、长度不足、tag 校验失败或 AAD 不匹配都抛出 {@link GeneralSecurityException}，调用方必须据此 fail closed。
   */
  public static byte[] decrypt(SecretKey key, int formatVersion, byte[] aad, byte[] envelope)
      throws GeneralSecurityException {
    requireAes256Key(key);
    requireFormatVersion(formatVersion);
    Objects.requireNonNull(aad, "aad");
    if (envelope == null || envelope.length <= OVERHEAD_BYTES) {
      throw new GeneralSecurityException("credential envelope is truncated");
    }
    if (envelope[0] != (byte) formatVersion) {
      throw new GeneralSecurityException("unsupported credential envelope format version");
    }
    byte[] nonce = Arrays.copyOfRange(envelope, 1, 1 + NONCE_BYTES);
    byte[] sealed = Arrays.copyOfRange(envelope, 1 + NONCE_BYTES, envelope.length);
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(aad);
    try {
      return cipher.doFinal(sealed);
    } catch (AEADBadTagException error) {
      throw new GeneralSecurityException("credential envelope failed authentication", error);
    }
  }

  private static void requireFormatVersion(int formatVersion) {
    if (formatVersion < 0 || formatVersion > 0xFF) {
      throw new IllegalArgumentException("formatVersion must fit an unsigned byte");
    }
  }

  private static void requireAes256Key(SecretKey key) {
    if (key == null || !"AES".equalsIgnoreCase(key.getAlgorithm())) {
      throw new IllegalArgumentException("credential key must be an AES key");
    }
    byte[] raw = key.getEncoded();
    if (raw == null || raw.length != KEY_BYTES) {
      throw new IllegalArgumentException("credential key must be exactly " + KEY_BYTES + " bytes");
    }
  }
}
