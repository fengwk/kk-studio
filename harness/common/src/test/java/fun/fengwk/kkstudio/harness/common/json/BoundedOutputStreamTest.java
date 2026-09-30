package fun.fengwk.kkstudio.harness.common.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** {@link BoundedOutputStream} 的字节精确计数、只计数模式与超限早停测试。 */
class BoundedOutputStreamTest {

  /** 中文（3 字节）与 emoji（4 字节代理对）都按实际 UTF-8 字节边界中止，不按字符数。 */
  @Test
  void countsCjkAndEmojiByExactUtf8Bytes() throws IOException {
    byte[] cjk = "你好".getBytes(StandardCharsets.UTF_8);
    assertEquals(6, cjk.length);
    BoundedOutputStream exactCjk = new BoundedOutputStream(cjk.length, true);
    exactCjk.write(cjk);
    assertEquals("你好", exactCjk.toUtf8String());
    BoundedOutputStream tooSmallCjk = new BoundedOutputStream(cjk.length - 1, true);
    assertThrows(BoundedOutputStream.LimitExceededException.class, () -> tooSmallCjk.write(cjk));
    // 超限是原子中止：整块被拒，未写入任何字节。
    assertEquals("", tooSmallCjk.toUtf8String());

    byte[] emoji = "😀".getBytes(StandardCharsets.UTF_8);
    assertEquals(4, emoji.length);
    BoundedOutputStream exactEmoji = new BoundedOutputStream(emoji.length, true);
    for (byte value : emoji) {
      exactEmoji.write(value);
    }
    assertEquals("😀", exactEmoji.toUtf8String());
    BoundedOutputStream partialEmoji = new BoundedOutputStream(emoji.length - 1);
    for (int index = 0; index < emoji.length - 1; index++) {
      partialEmoji.write(emoji[index]);
    }
    // 前 3 个字节已写入，第 4 个字节越界即中止。
    assertThrows(
        BoundedOutputStream.LimitExceededException.class, () -> partialEmoji.write(emoji[3]));
  }

  /** 只计数模式不保留任何字节，因此不能还原文本；保留模式才能按 UTF-8 还原。 */
  @Test
  void countingModeRetainsNothingWhileRetainingModeKeepsBytes() throws IOException {
    int size = 4 * 1024 * 1024;
    byte[] chunk = new byte[size];

    // 只计数：累计到上限仍不持有副本，也不因「物化完整输出」而分配大块内存。
    BoundedOutputStream counting = new BoundedOutputStream(size);
    counting.write(chunk);
    assertThrows(IllegalStateException.class, counting::toUtf8String);

    BoundedOutputStream retaining = new BoundedOutputStream(size, true);
    retaining.write(chunk);
    assertEquals(size, retaining.toUtf8String().length());
  }

  /** 只计数模式跨多次写入仍精确累计：刚好到上限通过，再写一个字节即中止。 */
  @Test
  void countingAccumulatesExactBytesAcrossWrites() throws IOException {
    BoundedOutputStream out = new BoundedOutputStream(6);
    byte[] three = new byte[3];
    out.write(three);
    out.write(three);
    assertThrows(BoundedOutputStream.LimitExceededException.class, () -> out.write(0));
  }

  /** 参数与字节数组索引校验：非正上限、非法 offset/length 都必须 fail fast。 */
  @Test
  void validatesLimitAndByteArrayIndexes() {
    assertThrows(IllegalArgumentException.class, () -> new BoundedOutputStream(0));
    assertThrows(IllegalArgumentException.class, () -> new BoundedOutputStream(-1));

    BoundedOutputStream out = new BoundedOutputStream(4);
    byte[] buffer = new byte[6];
    assertThrows(IndexOutOfBoundsException.class, () -> out.write(buffer, -1, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> out.write(buffer, 0, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> out.write(buffer, 2, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> out.write(buffer, 4, 3));
    assertThrows(BoundedOutputStream.LimitExceededException.class, () -> out.write(buffer, 0, 5));
  }
}
