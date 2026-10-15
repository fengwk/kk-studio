package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import fun.fengwk.kkstudio.platform.crypto.AesGcmEnvelope;
import fun.fengwk.kkstudio.platform.crypto.LengthPrefixedAad;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialIntegrityException;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * {@link LegacyPluginCredentialCodec} 的旧 format 1 只读契约，以及它与当前 format 2 的衔接边界。
 *
 * <p>合成 fixture 由测试独立按旧算法生成（{@link AesGcmEnvelope} + 旧 AAD 配方）：证明只读入口只认旧 format 1，真实 decrypt 后交由当前
 * {@link PluginCredentialCodec} 重封装仍逐字节保留 opaque 载荷与精确数字；错 key/id/region/tag/长度/版本与 Skill 令牌材料都必须被
 * 同一个去敏安全异常拒绝，且当前正常 codec 仍拒绝旧 format 1。
 */
class LegacyPluginCredentialCodecTest {

  private static final String PLUGIN_ID = "minimax-mavis";
  private static final String REGION = "CN";

  /** 不透明载荷：含超出 double 有效位的整数与小数，验证字符串级原样保留。 */
  private static final String PAYLOAD =
      "{\"token\":\"opaque-legacy-token\",\"count\":1234567890123456789,\"ratio\":1.5}";

  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String CONTEXT =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private final PluginCredentialCodec currentCodec = new PluginCredentialCodec();

  private static SecretKey key(byte fill) {
    byte[] raw = new byte[32];
    Arrays.fill(raw, fill);
    return new SecretKeySpec(raw, "AES");
  }

  /** 按旧 format 1 算法独立生成 fixture：格式 1、AAD=[pluginId,region,i32be(1)]、AES-256-GCM。 */
  private static byte[] legacyEnvelope(
      SecretKey key, String pluginId, String region, String payload) {
    byte[] aad = LengthPrefixedAad.create().field(pluginId).field(region).intValue(1).build();
    return AesGcmEnvelope.encrypt(key, 1, aad, payload.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void decryptsLegacyFormatOneAndReencryptsIntoCurrentFormatTwo() {
    byte[] legacy = legacyEnvelope(key((byte) 1), PLUGIN_ID, REGION, PAYLOAD);
    assertEquals(1, legacy[0], "fixture must be the frozen legacy format 1");

    String opaque = LegacyPluginCredentialCodec.decrypt(key((byte) 1), PLUGIN_ID, REGION, legacy);
    assertEquals(PAYLOAD, opaque, "the opaque payload must be returned byte-for-byte");

    byte[] current =
        currentCodec.encrypt(key((byte) 1), PLUGIN_ID, GENERATION, CONTEXT, REGION, opaque);
    assertEquals(PluginCredentialCodec.FORMAT_VERSION, current[0]);
    assertEquals(
        PAYLOAD,
        currentCodec.decrypt(key((byte) 1), PLUGIN_ID, GENERATION, CONTEXT, REGION, current),
        "re-encrypting must preserve opaque bytes and exact numbers");
  }

  @Test
  void rejectsEnvelopeReadWithAnotherKey() {
    byte[] legacy = legacyEnvelope(key((byte) 1), PLUGIN_ID, REGION, PAYLOAD);

    assertUnreadable(
        () -> LegacyPluginCredentialCodec.decrypt(key((byte) 2), PLUGIN_ID, REGION, legacy));
  }

  @Test
  void rejectsEnvelopeMovedToAnotherPluginIdOrRegion() {
    byte[] legacy = legacyEnvelope(key((byte) 1), PLUGIN_ID, REGION, PAYLOAD);

    assertUnreadable(
        () -> LegacyPluginCredentialCodec.decrypt(key((byte) 1), "other", REGION, legacy));
    assertUnreadable(
        () -> LegacyPluginCredentialCodec.decrypt(key((byte) 1), PLUGIN_ID, "EN", legacy));
  }

  @Test
  void rejectsTamperedCiphertextTagOrUnknownVersion() {
    SecretKey key = key((byte) 1);
    for (int index : new int[] {1, 13, 20}) {
      byte[] legacy = legacyEnvelope(key, PLUGIN_ID, REGION, PAYLOAD);
      legacy[index] = (byte) (legacy[index] ^ 0x01);
      assertUnreadable(() -> LegacyPluginCredentialCodec.decrypt(key, PLUGIN_ID, REGION, legacy));
    }

    byte[] currentVersion = legacyEnvelope(key, PLUGIN_ID, REGION, PAYLOAD);
    currentVersion[0] = (byte) PluginCredentialCodec.FORMAT_VERSION;
    assertUnreadable(
        () -> LegacyPluginCredentialCodec.decrypt(key, PLUGIN_ID, REGION, currentVersion));
  }

  @Test
  void rejectsTruncatedEmptyOrNullEnvelope() {
    SecretKey key = key((byte) 1);
    byte[] legacy = legacyEnvelope(key, PLUGIN_ID, REGION, PAYLOAD);

    assertUnreadable(
        () -> LegacyPluginCredentialCodec.decrypt(key, PLUGIN_ID, REGION, new byte[0]));
    assertUnreadable(
        () ->
            LegacyPluginCredentialCodec.decrypt(key, PLUGIN_ID, REGION, Arrays.copyOf(legacy, 28)));
    assertUnreadable(
        () ->
            LegacyPluginCredentialCodec.decrypt(
                key,
                PLUGIN_ID,
                REGION,
                Arrays.copyOf(legacy, LegacyPluginCredentialCodec.FORMAT_ONE_OVERHEAD_BYTES)));
    assertUnreadable(() -> LegacyPluginCredentialCodec.decrypt(key, PLUGIN_ID, REGION, null));
  }

  /** Skill 令牌 fmt1 是另一命名空间：本工具不得把它当作 Plugin 凭据自动读取。 */
  @Test
  void doesNotReadSkillFormatOneMaterial() {
    SecretKey key = key((byte) 1);
    byte[] skillAad =
        LengthPrefixedAad.create().field("skill-package").field("my-skill").intValue(1).build();
    byte[] skillEnvelope =
        AesGcmEnvelope.encrypt(key, 1, skillAad, "skill-token".getBytes(StandardCharsets.UTF_8));

    assertUnreadable(
        () -> LegacyPluginCredentialCodec.decrypt(key, PLUGIN_ID, REGION, skillEnvelope));
  }

  /** 当前生产 codec 仍只认 format 2：旧 format 1 密文必须被它拒绝。 */
  @Test
  void currentCodecStillRejectsLegacyFormatOne() {
    byte[] legacy = legacyEnvelope(key((byte) 1), PLUGIN_ID, REGION, PAYLOAD);

    assertThrows(
        PluginCredentialIntegrityException.class,
        () -> currentCodec.decrypt(key((byte) 1), PLUGIN_ID, GENERATION, CONTEXT, REGION, legacy));
  }

  @Test
  void requiresNonNullPluginIdAndRegion() {
    assertThrows(
        IllegalArgumentException.class,
        () -> LegacyPluginCredentialCodec.decrypt(key((byte) 1), null, REGION, new byte[30]));
    assertThrows(
        IllegalArgumentException.class,
        () -> LegacyPluginCredentialCodec.decrypt(key((byte) 1), PLUGIN_ID, " ", new byte[30]));
  }

  /** 不可读密文一律收敛为同一个去敏异常：固定消息、无底层 cause。 */
  private static void assertUnreadable(Executable executable) {
    LegacyPluginCredentialCodec.LegacyCredentialUnreadableException failure =
        assertThrows(
            LegacyPluginCredentialCodec.LegacyCredentialUnreadableException.class, executable);
    assertNull(failure.getCause(), "no underlying cause may leak");
    assertTrue(
        failure.getMessage().contains("legacy plugin credential envelope is not readable"),
        failure.getMessage());
  }
}
