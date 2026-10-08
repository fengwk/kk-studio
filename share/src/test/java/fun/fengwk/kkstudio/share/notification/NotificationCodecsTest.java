package fun.fengwk.kkstudio.share.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 静态领域 topic 共用的 canonical scalar codec 契约。 */
class NotificationCodecsTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
  private static final UUID LETTER_ID = UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab");

  /** lone UTF-16 surrogate 必须拒绝：绝不静默编码成 '?' 让本地编码与远端解码语义不一致。 */
  @Test
  void textCodecRejectsLoneSurrogateInsteadOfSilentReplacement() {
    String payload = "name-\uD800";

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> NotificationCodecs.NAME.encode(payload));

    assertFalse(error.getMessage().contains(payload), "异常不得回显 payload 内容");
    assertThrows(IllegalArgumentException.class, () -> NotificationCodecs.encodeUtf8(payload));
  }

  /** 合法补充平面字符必须严格编码为与 JDK UTF-8 完全一致的字节，并可无损往返。 */
  @Test
  void textCodecStrictlyEncodesValidSurrogatePair() {
    String payload = "名字-\uD83D\uDE00";

    byte[] encoded = NotificationCodecs.NAME.encode(payload);

    assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), encoded);
    assertEquals(payload, NotificationCodecs.NAME.decode(encoded));
  }

  /** 坏字节必须拒绝：宽松解码会把它们替换成 U+FFFD，让不同字节序列产生同一语义。 */
  @Test
  void textCodecRejectsMalformedUtf8() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.NAME.decode(new byte[] {(byte) 0xFF}));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.NAME.decode(new byte[] {(byte) 0xE5, (byte) 0xA5}));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.decodeUtf8(new byte[] {(byte) 0xC0, (byte) 0x80}));
  }

  /** canonical 标量规则：UUID 与 version 只接受规范文本，非规范大小写、前导零与负值一律拒绝。 */
  @Test
  void scalarCodecsRejectNonCanonicalText() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000007");
    assertEquals(
        id, NotificationCodecs.UUID_CODEC.decode(NotificationCodecs.UUID_CODEC.encode(id)));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.UUID_CODEC.decode(bytes("not-a-uuid")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.UUID_CODEC.decode(bytes("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB")));

    assertEquals(7L, NotificationCodecs.VERSION.decode(bytes("7")));
    assertThrows(
        IllegalArgumentException.class, () -> NotificationCodecs.VERSION.decode(bytes("07")));
    assertThrows(
        IllegalArgumentException.class, () -> NotificationCodecs.VERSION.decode(bytes("-1")));
  }

  /** NAME 拒绝空白与内嵌 NUL，避免产生无法区分的名字。 */
  @Test
  void nameCodecRejectsBlankAndNul() {
    assertEquals("ok", NotificationCodecs.NAME.decode(bytes("ok")));
    assertThrows(IllegalArgumentException.class, () -> NotificationCodecs.NAME.decode(bytes("  ")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.NAME.decode(new byte[] {'a', 0, 'b'}));
  }

  /** VERSION 只接受规范非负十进字符串：encode 侧同样走 canonical 校验。 */
  @Test
  void versionCodecRejectsNonCanonicalNumbers() {
    assertArrayEquals(bytes("7"), NotificationCodecs.VERSION.encode(7L));
    assertEquals(
        Long.MAX_VALUE, NotificationCodecs.VERSION.decode(bytes(Long.toString(Long.MAX_VALUE))));

    assertThrows(IllegalArgumentException.class, () -> NotificationCodecs.VERSION.encode(-1L));
    assertThrows(
        IllegalArgumentException.class, () -> NotificationCodecs.VERSION.decode(bytes("+7")));
    assertThrows(
        IllegalArgumentException.class, () -> NotificationCodecs.VERSION.decode(bytes("1.0")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.VERSION.decode(bytes("9223372036854775808")));
  }

  /** VERSION_HINT 是实体 id 与真实 version 的 canonical 组合，字段数与两段内容都必须规范。 */
  @Test
  void versionHintCodecRoundTripsAndRejectsNonCanonicalText() {
    VersionHint hint = new VersionHint(ID, 7L);

    byte[] encoded = NotificationCodecs.VERSION_HINT.encode(hint);

    assertArrayEquals(bytes(ID + ":7"), encoded);
    assertEquals(hint, NotificationCodecs.VERSION_HINT.decode(encoded));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.VERSION_HINT.decode(bytes(ID.toString())));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.VERSION_HINT.decode(bytes(ID + ":7:8")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.VERSION_HINT.decode(bytes(ID + ":07")));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.VERSION_HINT.decode(bytes("not-a-uuid:7")));
  }

  /** ENTITY_HINT 的空实体表示来源事实缺失：空载荷合法往返，非空载荷必须是 canonical UUID。 */
  @Test
  void entityHintCodecRoundTripsMissingAndCanonicalEntity() {
    assertArrayEquals(new byte[0], NotificationCodecs.ENTITY_HINT.encode(new EntityHint(null)));
    assertEquals(new EntityHint(null), NotificationCodecs.ENTITY_HINT.decode(new byte[0]));

    assertArrayEquals(
        bytes(ID.toString()), NotificationCodecs.ENTITY_HINT.encode(new EntityHint(ID)));
    assertEquals(new EntityHint(ID), NotificationCodecs.ENTITY_HINT.decode(bytes(ID.toString())));

    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.ENTITY_HINT.decode(bytes("not-a-uuid")));
    assertEquals(
        new EntityHint(LETTER_ID),
        NotificationCodecs.ENTITY_HINT.decode(bytes(LETTER_ID.toString())));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.ENTITY_HINT.decode(bytes("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB")));
  }

  /** SIGNAL 只允许空载荷，表示无参数的变更提示。 */
  @Test
  void signalCodecAcceptsOnlyEmptyPayload() {
    assertArrayEquals(new byte[0], NotificationCodecs.SIGNAL.encode(NotificationSignal.CHANGED));
    assertEquals(NotificationSignal.CHANGED, NotificationCodecs.SIGNAL.decode(new byte[0]));
    assertThrows(
        IllegalArgumentException.class, () -> NotificationCodecs.SIGNAL.decode(bytes("x")));
  }

  /** UUID_CODEC 编码为 canonical 小写文本。 */
  @Test
  void uuidCodecEncodesCanonicalText() {
    assertArrayEquals(bytes(ID.toString()), NotificationCodecs.UUID_CODEC.encode(ID));
    assertEquals(ID, NotificationCodecs.UUID_CODEC.decode(bytes(ID.toString())));
  }

  /** canonical 守卫：decode 接受但重新编码得不到同一文本时必须拒绝。 */
  @Test
  void textCodecRejectsPayloadWhoseCanonicalFormDiffers() {
    NotificationCodec<String> trimming =
        NotificationCodecs.text(value -> value.trim(), value -> value);

    assertEquals("x", trimming.decode(bytes("x")));
    assertThrows(IllegalArgumentException.class, () -> trimming.decode(bytes(" x ")));
  }

  /** 严格 UTF-8 边界：空输入往返，孤立高低位 surrogate 与 UTF-8 编码的 surrogate 一律拒绝。 */
  @Test
  void strictUtf8HandlesEmptyAndRejectsLoneSurrogates() {
    assertArrayEquals(new byte[0], NotificationCodecs.encodeUtf8(""));
    assertEquals("", NotificationCodecs.decodeUtf8(new byte[0]));

    assertThrows(IllegalArgumentException.class, () -> NotificationCodecs.encodeUtf8("a\uDC00"));
    assertThrows(IllegalArgumentException.class, () -> NotificationCodecs.encodeUtf8("\uD83D"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NotificationCodecs.decodeUtf8(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
