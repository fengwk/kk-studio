package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * {@link TextFileCodec} 的文本判定与无损编码契约测试（write/edit/grep 共用）。
 *
 * <p>用例来源与排除理由记录在 docs/operations/builtin-mutation-tests.md：本切片只处理带 BOM 的 UTF-8/UTF-16 与无 BOM 的
 * UTF-8， 不引入旧编码猜测或“解码后控制字符密度”启发式，因此 pi-base 中的 GBK/latin1 与无 BOM UTF-16 用例不在契约范围内。
 */
class TextFileCodecTest {

  /**
   * 来源：pi-base text-codec「detects BOMs and UTF-16 without a BOM」的 BOM 部分（无 BOM UTF-16 启发式不在契约内）。
   */
  @Test
  void decodesEverySupportedBomFormWithItsExactEncoding() {
    TextFileCodec.Decoded utf8Bom =
        TextFileCodec.decode(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'h', 'i'});
    assertEquals("hi", utf8Bom.text());
    assertEquals(StandardCharsets.UTF_8, utf8Bom.charset());
    assertEquals(3, utf8Bom.bomLength());

    byte[] utf16leBytes = new byte[] {(byte) 0xff, (byte) 0xfe, 'h', 0, 'i', 0};
    TextFileCodec.Decoded utf16le = TextFileCodec.decode(utf16leBytes);
    assertEquals("hi", utf16le.text());
    assertEquals(StandardCharsets.UTF_16LE, utf16le.charset());
    assertEquals(2, utf16le.bomLength());
    assertArrayEquals(
        utf16leBytes, TextFileCodec.encode("hi", utf16le.charset(), utf16le.bomLength()));

    byte[] utf16beBytes = new byte[] {(byte) 0xfe, (byte) 0xff, 0, 'h', 0, 'i'};
    TextFileCodec.Decoded utf16be = TextFileCodec.decode(utf16beBytes);
    assertEquals("hi", utf16be.text());
    assertEquals(StandardCharsets.UTF_16BE, utf16be.charset());
    assertEquals(2, utf16be.bomLength());
    assertArrayEquals(
        utf16beBytes, TextFileCodec.encode("hi", utf16be.charset(), utf16be.bomLength()));

    TextFileCodec.Decoded plain = TextFileCodec.decode("hi".getBytes(StandardCharsets.UTF_8));
    assertEquals("hi", plain.text());
    assertEquals(0, plain.bomLength());
  }

  /**
   * 来源：pi-base text-codec「distinguishes true binary data from valid replacement-character text」。
   */
  @Test
  void distinguishesReplacementCharacterTextFromBinaryBytes() {
    // 合法 UTF-8 的 U+FFFD 是文本，不得被判成二进制。
    TextFileCodec.Decoded replacement =
        TextFileCodec.decode("\uFFFD".getBytes(StandardCharsets.UTF_8));
    assertEquals("\uFFFD", replacement.text());

    // 空文件是合法文本。
    assertEquals("", TextFileCodec.decode(new byte[0]).text());

    // NUL 与无法严格解码的字节序列按二进制拒绝。
    assertThrows(
        IllegalArgumentException.class, () -> TextFileCodec.decode(new byte[] {0x00, 0x41}));
    assertThrows(
        IllegalArgumentException.class, () -> TextFileCodec.decode(new byte[] {(byte) 0xff}));
  }

  /** 来源：pi-base text-codec「rejects NUL-bearing binary samples after BOM and UTF-16 checks」。 */
  @Test
  void rejectsNulBearingBinarySamples() {
    byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01, 0x02, 0x03};
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(png));

    // 带 BOM 的 UTF-16 文本本身包含 NUL 字节，仍必须按文本解码。
    assertEquals(
        "AB",
        TextFileCodec.decode(TextFileCodec.encode("AB", StandardCharsets.UTF_16LE, 2)).text());
  }

  /**
   * 来源：pi-base text-codec「treats common whitespace as text」「falls back to UTF-8 for suspicious
   * decoded text」。
   */
  @Test
  void treatsWhitespaceAndEscapeBytesAsText() {
    assertEquals(
        "alpha\tbeta\n",
        TextFileCodec.decode("alpha\tbeta\n".getBytes(StandardCharsets.UTF_8)).text());

    String escapes = "\u001b\u001b\u001b\u001b\u001bA";
    assertEquals(escapes, TextFileCodec.decode(escapes.getBytes(StandardCharsets.UTF_8)).text());
  }

  /** 来源：pi-base text-codec「maps encodings to BOM kinds and validates lossy writes」。 */
  @Test
  void rejectsLossyEncodingAndInvalidArguments() {
    // 内容无法在目标编码下无损表示时拒绝写入，而不是静默替换成占位字符。
    assertThrows(
        IllegalArgumentException.class,
        () -> TextFileCodec.encode("你好", StandardCharsets.US_ASCII, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> TextFileCodec.encode("snow \ud800", StandardCharsets.UTF_16LE, 2));

    // 以 U+FEFF 开头的内容按编码自然写出对应 BOM 字节。
    assertArrayEquals(
        new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'h'},
        TextFileCodec.encode("\uFEFFh", StandardCharsets.UTF_8, 0));
    // 既有 BOM 与内容自身的 U+FEFF 都按各自语义落盘：结果仍能按同一编码无损读回。
    assertEquals(
        "\uFEFFh",
        TextFileCodec.decode(TextFileCodec.encode("\uFEFFh", StandardCharsets.UTF_16LE, 2)).text());

    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> TextFileCodec.encode(null, StandardCharsets.UTF_8, 0));
  }
}
