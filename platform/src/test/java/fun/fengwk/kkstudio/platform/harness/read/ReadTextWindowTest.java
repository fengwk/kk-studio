package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/** {@link ReadTextWindow} 文本窗口格式化工具的单元测试。 */
class ReadTextWindowTest {

  /** 空文件输出 0 行元数据与 [Showing 0 lines of 0.] */
  @Test
  void emptyInputFormatsZeroLinesHeader() {
    byte[] bytes = new byte[0];
    String result = ReadTextWindow.format(bytes, 1, 200, null, "test.txt");

    String expected =
        """
        path: test.txt
        ends_with_newline: no
        lsp: unsupported

        [Showing 0 lines of 0.]""";
    assertEquals(expected, result);
  }

  /** 正常 LF 分隔的文本能够正确编号输出并展示 header */
  @Test
  void normalTextWithLfLineSeparator() {
    byte[] bytes = "line1\nline2\n".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 200, null, "test.txt");

    String expected =
        """
        path: test.txt
        ends_with_newline: yes
        lsp: unsupported

        1|line1
        2|line2""";
    assertEquals(expected, result);
  }

  /** CRLF 与孤立 CR 均能作为换行符被正确归一化处理 */
  @Test
  void crlfAndLoneCrNormalizedAsLineSeparators() {
    byte[] bytes = "a\r\nb\rc".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 200, null, "test.txt");

    String expected =
        """
        path: test.txt
        ends_with_newline: no
        lsp: unsupported

        1|a
        2|b
        3|c""";
    assertEquals(expected, result);
  }

  /** 文件末尾带换行时 ends_with_newline 标记为 yes */
  @Test
  void textEndingWithNewlineReportsYes() {
    byte[] bytes = "hello\n".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 200, null, "test.txt");

    assertTrue(result.contains("ends_with_newline: yes"));
  }

  /** 文件末尾无换行时 ends_with_newline 标记为 no */
  @Test
  void textNotEndingWithNewlineReportsNo() {
    byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 200, null, "test.txt");

    assertTrue(result.contains("ends_with_newline: no"));
  }

  /** offset 超过总行数时输出 Showing 0 lines 提示 */
  @Test
  void offsetBeyondTotalLinesReportsZeroLines() {
    byte[] bytes = "line1\nline2\n".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 5, 200, null, "test.txt");

    assertTrue(result.contains("[Showing 0 lines of 2.]"));
  }

  /** 指定 limit 截断时输出下一页继续读取的 footer 提示 */
  @Test
  void pagingWithLimitAppendsContinuationFooter() {
    byte[] bytes = "line1\nline2\nline3\n".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 2, null, "test.txt");

    assertTrue(result.contains("[Showing lines 1-2 of 3. Re-run read with offset=3 to continue.]"));
  }

  /** 单行超过 2000 code points 时截断并输出列分页 footer */
  @Test
  void lineExceedingCodePointsLimitAppendsColumnFooter() {
    String longLine = "a".repeat(2500);
    byte[] bytes = longLine.getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 200, null, "test.txt");

    assertTrue(
        result.contains(
            "[Showing columns 1-2000 of 2500 on line 1. Re-run read with offset=1, limit=1, column_offset=2001 to continue.]"));
  }

  /** 指定 column_offset 时按指定起始列截取行内容 */
  @Test
  void columnOffsetSlicesLineRange() {
    byte[] bytes = "abcdefghij".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 1, 4, "test.txt");

    assertTrue(result.contains("1|defghij"));
    assertTrue(result.contains("[Showing columns 4-10 of 10 on line 1.]"));
  }

  /** 指定 column_offset 时 limit 不为 1 抛出异常 */
  @Test
  void columnOffsetWithLimitOtherThanOneThrowsException() {
    byte[] bytes = "abcdefghij".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PlatformReadException.class, () -> ReadTextWindow.format(bytes, 1, 2, 4, "test.txt"));
  }

  /** column_offset 超过单行列宽时输出 0 columns footer */
  @Test
  void columnOffsetBeyondLineLengthOutputsZeroColumnsFooter() {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 1, 10, "test.txt");

    assertTrue(result.contains("[Showing 0 columns of 3 on line 1.]"));
    assertFalse(result.contains("1|"));
  }

  /** 包含 UTF-8 BOM 前缀的字节能够正确剥离 BOM 并解码 */
  @Test
  void utf8WithBomStripsBomProperly() {
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] content = "bom-text\n".getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[bom.length + content.length];
    System.arraycopy(bom, 0, all, 0, bom.length);
    System.arraycopy(content, 0, all, bom.length, content.length);

    String result = ReadTextWindow.format(all, 1, 200, null, "test.txt");
    assertTrue(result.contains("1|bom-text"));
  }

  /** 包含 NUL 字节的文件判定为二进制并抛出异常 */
  @Test
  void binaryFileWithNulByteThrowsException() {
    byte[] bytes = new byte[] {'a', 'b', 0, 'c'};
    assertThrows(
        PlatformReadException.class, () -> ReadTextWindow.format(bytes, 1, 200, null, "test.bin"));
  }

  /** 非法 UTF-8 编码的字节序列判定为二进制并抛出异常 */
  @Test
  void malformedUtf8ThrowsException() {
    byte[] bytes = new byte[] {(byte) 0xC0, (byte) 0xAF};
    assertThrows(
        PlatformReadException.class, () -> ReadTextWindow.format(bytes, 1, 200, null, "test.bin"));
  }

  /** 第一行即超过 48 KiB 响应上限时直接抛出异常 */
  @Test
  void firstLineExceedingMaxResponseBytesThrowsException() {
    // 构造极长行使得单行格式化后的候选字节超过 48 KiB (例如通过超长文件名或路径等放大)
    String hugePath = "p".repeat(50 * 1024);
    byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PlatformReadException.class, () -> ReadTextWindow.format(bytes, 1, 200, null, hugePath));
  }

  /** 输入源字节数超过 8 MiB 上限时直接抛出异常 */
  @Test
  void exceedingMaxSourceBytesThrowsException() {
    byte[] hugeBytes = new byte[ReadTextWindow.MAX_SOURCE_BYTES + 1];
    assertThrows(
        PlatformReadException.class,
        () -> ReadTextWindow.format(hugeBytes, 1, 200, null, "huge.txt"));
  }

  /** offset 小于 1 时抛出异常 */
  @Test
  void invalidOffsetThrowsException() {
    byte[] bytes = "text".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PlatformReadException.class, () -> ReadTextWindow.format(bytes, 0, 200, null, "test.txt"));
  }

  /** limit 小于 1 或超过 2000 时抛出异常 */
  @Test
  void invalidLimitThrowsException() {
    byte[] bytes = "text".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        PlatformReadException.class, () -> ReadTextWindow.format(bytes, 1, 0, null, "test.txt"));
    assertThrows(
        PlatformReadException.class, () -> ReadTextWindow.format(bytes, 1, 2001, null, "test.txt"));
  }

  /** 多行累加超过 48 KiB 上限时截断并生成分页 footer */
  @Test
  void responseBytesLimitTruncatesMultipleLinesWithFooter() {
    // 构造很多长度适中的行（如每行 500 字符，总共 200 行），累计远超 48 KiB
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append("x".repeat(500)).append("\n");
    }
    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
    String result = ReadTextWindow.format(bytes, 1, 200, null, "multi.txt");

    assertTrue(result.contains("[Showing lines 1-"));
    assertTrue(result.contains("of 200. Re-run read with offset="));
  }
}
