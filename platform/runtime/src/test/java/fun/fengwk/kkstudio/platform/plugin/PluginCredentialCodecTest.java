package fun.fengwk.kkstudio.platform.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * AES-256-GCM 凭据 envelope 的认证加密契约。
 *
 * <p>这些测试是「密文只能在其 pluginId / region / 认证代际上解密一次」这一安全属性的可执行证据：任何把 envelope 搬运到其它身份、代际、翻转 任一
 * bit、截断、改版本或换密钥的尝试都必须在解密阶段失败，而不是返回部分明文。
 */
class PluginCredentialCodecTest {

  private static final String PLUGIN_ID = "minimax-mavis";
  private static final String REGION = "CN";
  private static final String PAYLOAD = "{\"token\":\"opaque-secret-token\"}";
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_GENERATION =
      UUID.fromString("22222222-2222-2222-2222-222222222222");

  private final PluginCredentialCodec codec = new PluginCredentialCodec();

  private static SecretKey key(byte fill) {
    byte[] raw = new byte[PluginCredentialKeyLoader.KEY_BYTES];
    Arrays.fill(raw, fill);
    return new SecretKeySpec(raw, "AES");
  }

  /** 同身份加密解密必须还原原文，且 envelope 首字节是当前格式版本。 */
  @Test
  void roundTripsPayloadForTheSameIdentity() {
    byte[] envelope = codec.encrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertEquals(PluginCredentialCodec.FORMAT_VERSION, envelope[0]);
    assertEquals(2, PluginCredentialCodec.FORMAT_VERSION, "Plugin 凭据只存在格式 2");
    assertEquals(PAYLOAD, codec.decrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, envelope));
  }

  /** 每次加密都必须使用新的随机 nonce，否则同一明文会产生可关联的密文。 */
  @Test
  void usesFreshNoncePerEncryption() {
    SecretKey key = key((byte) 1);
    byte[] first = codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, PAYLOAD);
    byte[] second = codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertFalse(Arrays.equals(first, second), "envelopes must differ because the nonce differs");
    assertFalse(
        Arrays.equals(
            Arrays.copyOfRange(first, 1, 1 + AesGcmEnvelope.NONCE_BYTES),
            Arrays.copyOfRange(second, 1, 1 + AesGcmEnvelope.NONCE_BYTES)),
        "nonces must not repeat");
    assertEquals(PAYLOAD, codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, second));
  }

  /** envelope 被绑定到 pluginId：把密文换到另一个 Plugin 上必须认证失败，而不是解出别人的秘密。 */
  @Test
  void rejectsEnvelopeMovedToAnotherPluginId() {
    byte[] envelope = codec.encrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key((byte) 1), "other-plugin", GENERATION, REGION, envelope));
  }

  /** envelope 同样绑定 region：跨 region 复用必须认证失败。 */
  @Test
  void rejectsEnvelopeMovedToAnotherRegion() {
    byte[] envelope = codec.encrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key((byte) 1), PLUGIN_ID, GENERATION, "EN", envelope));
  }

  /** envelope 绑定认证代际：同 pluginId / region 但代际不同必须认证失败。 */
  @Test
  void rejectsEnvelopeMovedToAnotherGeneration() {
    byte[] envelope = codec.encrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key((byte) 1), PLUGIN_ID, OTHER_GENERATION, REGION, envelope));
  }

  /** 不同 pluginId / region 的 AAD 必须无歧义拼接，否则字段边界可被错位搬运。 */
  @Test
  void bindsAadWithoutFieldConcatenationAmbiguity() {
    SecretKey key = key((byte) 1);
    byte[] withSplitFields = codec.encrypt(key, "ab", GENERATION, "cd", PAYLOAD);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key, "abc", GENERATION, "d", withSplitFields));
  }

  /** 任何密文或 tag 的 bit 翻转都必须被 GCM 认证发现。 */
  @Test
  void rejectsTamperedCiphertextOrTag() {
    SecretKey key = key((byte) 1);
    for (int index : new int[] {1, 13, 20}) {
      byte[] envelope = codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, PAYLOAD);
      envelope[index] = (byte) (envelope[index] ^ 0x01);

      assertThrows(
          PluginCredentialIntegrityException.class,
          () -> codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, envelope),
          "flipping byte " + index + " must fail authentication");
    }
  }

  /** 未知格式版本必须拒绝，避免把未来格式误读为当前格式。 */
  @Test
  void rejectsUnknownFormatVersion() {
    SecretKey key = key((byte) 1);
    byte[] envelope = codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, PAYLOAD);
    envelope[0] = (byte) (PluginCredentialCodec.FORMAT_VERSION + 1);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, envelope));
  }

  /** 旧的格式 1 Plugin 密文必须被拒绝：当前实现不提供旧格式双读。 */
  @Test
  void rejectsLegacyFormatOneEnvelope() {
    SecretKey key = key((byte) 1);
    byte[] envelope = codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, PAYLOAD);
    envelope[0] = 1;

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, envelope));
  }

  /** 截断或空 envelope 不得触发任何未认证的解密尝试。 */
  @Test
  void rejectsTruncatedEnvelope() {
    SecretKey key = key((byte) 1);
    byte[] envelope = codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, new byte[0]));
    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, Arrays.copyOf(envelope, 28)));
    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, null));
  }

  /** 主密钥轮换后旧密文必须不可读：这是「密文不能跨密钥复用」的证据。 */
  @Test
  void rejectsEnvelopeEncryptedWithAnotherKey() {
    byte[] envelope = codec.encrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> codec.decrypt(key((byte) 2), PLUGIN_ID, GENERATION, REGION, envelope));
    assertEquals(PAYLOAD, codec.decrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, envelope));
  }

  /** 非 32 bytes / 非 AES 的密钥必须在进入密码学前被拒绝，避免静默降级到弱密钥。 */
  @Test
  void rejectsKeyMaterialThatIsNotAes256() {
    SecretKey shortKey = new SecretKeySpec(new byte[16], "AES");
    SecretKey wrongAlgorithm =
        new SecretKeySpec(new byte[PluginCredentialKeyLoader.KEY_BYTES], "HmacSHA256");
    SecretKey valid = key((byte) 1);
    byte[] envelope = codec.encrypt(valid, PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encrypt(shortKey, PLUGIN_ID, GENERATION, REGION, PAYLOAD));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encrypt(wrongAlgorithm, PLUGIN_ID, GENERATION, REGION, PAYLOAD));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decrypt(shortKey, PLUGIN_ID, GENERATION, REGION, envelope));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encrypt(null, PLUGIN_ID, GENERATION, REGION, PAYLOAD));
  }

  /** 载荷与身份字段必须显式给出，缺失时不得构造出无绑定的密文。 */
  @Test
  void rejectsMissingPayloadOrIdentity() {
    SecretKey key = key((byte) 1);

    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encrypt(key, null, GENERATION, REGION, PAYLOAD));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encrypt(key, PLUGIN_ID, GENERATION, null, PAYLOAD));
    assertThrows(
        IllegalArgumentException.class, () -> codec.encrypt(key, PLUGIN_ID, null, REGION, PAYLOAD));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decrypt(key, PLUGIN_ID, null, REGION, new byte[] {2, 1, 2, 3}));
  }

  /** 空载荷是合法状态（Plugin 可能返回无字段 JSON），必须仍可往返。 */
  @Test
  void roundTripsEmptyPayload() {
    SecretKey key = key((byte) 1);
    byte[] envelope = codec.encrypt(key, PLUGIN_ID, GENERATION, REGION, "{}");

    assertEquals("{}", codec.decrypt(key, PLUGIN_ID, GENERATION, REGION, envelope));
    assertNotEquals(0, envelope.length);
  }

  /** 密文不得把明文原样带出，避免「加密实现其实没加密」。 */
  @Test
  void cipherTextDoesNotContainPlainText() {
    byte[] envelope = codec.encrypt(key((byte) 1), PLUGIN_ID, GENERATION, REGION, PAYLOAD);

    byte[] plain = PAYLOAD.getBytes(StandardCharsets.UTF_8);
    assertFalse(indexOf(envelope, plain) >= 0, "plaintext must not appear inside the envelope");
  }

  private static int indexOf(byte[] source, byte[] target) {
    for (int i = 0; i <= source.length - target.length; i++) {
      if (Arrays.equals(Arrays.copyOfRange(source, i, i + target.length), target)) {
        return i;
      }
    }
    return -1;
  }
}
