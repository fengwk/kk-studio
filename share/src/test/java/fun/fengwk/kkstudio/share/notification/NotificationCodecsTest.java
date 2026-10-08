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

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
