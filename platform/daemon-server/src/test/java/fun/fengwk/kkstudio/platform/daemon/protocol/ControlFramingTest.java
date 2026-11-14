package fun.fengwk.kkstudio.platform.daemon.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/** 四字节 big-endian 长度前缀的精确边界测试。 */
class ControlFramingTest {

  private static final int PREFIX_BYTES = Integer.BYTES;

  /** 16 MiB 是允许的最大消息体，恰好包含边界必须成功，且前缀为大端长度。 */
  @Test
  void acceptsExactlySixteenMebibytes() {
    byte[] message = new byte[ControlFraming.MAX_MESSAGE_BYTES];
    message[0] = 1;
    message[message.length - 1] = 2;

    byte[] framed = ControlFraming.frame(message);

    assertEquals(ControlFraming.MAX_MESSAGE_BYTES + PREFIX_BYTES, framed.length);
    assertArrayEquals(
        new byte[] {0x01, 0x00, 0x00, 0x00},
        new byte[] {framed[0], framed[1], framed[2], framed[3]});
    assertArrayEquals(message, Arrays.copyOfRange(framed, PREFIX_BYTES, framed.length));
  }

  /** 空消息和 16 MiB + 1 字节都在分配帧之前拒绝。 */
  @Test
  void rejectsEmptyAndOversizedMessages() {
    assertThrows(ControlProtocolException.class, () -> ControlFraming.frame(null));
    assertThrows(ControlProtocolException.class, () -> ControlFraming.frame(new byte[0]));
    assertThrows(
        ControlProtocolException.class,
        () -> ControlFraming.frame(new byte[ControlFraming.MAX_MESSAGE_BYTES + 1]));
  }

  /** 小消息同样使用大端前缀。 */
  @Test
  void encodesBigEndianPrefix() {
    assertArrayEquals(new byte[] {0, 0, 0, 3, 1, 2, 3}, ControlFraming.frame(new byte[] {1, 2, 3}));
  }
}
