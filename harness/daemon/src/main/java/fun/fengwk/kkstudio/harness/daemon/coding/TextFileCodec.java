package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** write 与 edit 使用的严格文本编码/BOM 保留与检测实现。 */
final class TextFileCodec {

  private TextFileCodec() {}

  static Decoded decode(byte[] bytes) {
    if (bytes == null) {
      throw new IllegalArgumentException("bytes must not be null");
    }
    if (starts(bytes, (byte) 0xef, (byte) 0xbb, (byte) 0xbf)) {
      return decodeStrict(bytes, 3, StandardCharsets.UTF_8);
    }
    if (starts(bytes, (byte) 0xff, (byte) 0xfe)) {
      return decodeStrict(bytes, 2, StandardCharsets.UTF_16LE);
    }
    if (starts(bytes, (byte) 0xfe, (byte) 0xff)) {
      return decodeStrict(bytes, 2, StandardCharsets.UTF_16BE);
    }
    if (isBinary(bytes)) {
      throw new IllegalArgumentException("file appears to be binary");
    }
    return decodeStrict(bytes, 0, StandardCharsets.UTF_8);
  }

  static byte[] encode(String text, Charset charset, int bomLength) {
    if (text == null) {
      throw new IllegalArgumentException("text must not be null");
    }
    CharsetEncoder encoder =
        charset
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer encoded;
    try {
      encoded = encoder.encode(CharBuffer.wrap(text));
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(
          "text cannot be losslessly encoded in charset " + charset.name(), error);
    }

    byte[] content = new byte[encoded.remaining()];
    encoded.get(content);
    if (bomLength == 0) {
      return content;
    }

    byte[] bom =
        charset.equals(StandardCharsets.UTF_8)
            ? new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
            : charset.equals(StandardCharsets.UTF_16LE)
                ? new byte[] {(byte) 0xff, (byte) 0xfe}
                : new byte[] {(byte) 0xfe, (byte) 0xff};
    byte[] result = Arrays.copyOf(bom, bom.length + content.length);
    System.arraycopy(content, 0, result, bom.length, content.length);
    return result;
  }

  static boolean isBinary(byte[] bytes) {
    int scan = Math.min(bytes.length, 8192);
    for (int index = 0; index < scan; index++) {
      int value = bytes[index] & 0xff;
      if (value == 0 || (value < 0x09) || (value > 0x0d && value < 0x20 && value != 0x1b)) {
        return true;
      }
    }
    return false;
  }

  private static Decoded decodeStrict(byte[] bytes, int bomLength, Charset charset) {
    CharsetDecoder decoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer buffer = ByteBuffer.wrap(bytes, bomLength, bytes.length - bomLength);
    CharBuffer chars;
    try {
      chars = decoder.decode(buffer);
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("file appears to be binary: invalid text encoding", error);
    }
    String text = chars.toString();
    if (bomLength == 0 && text.indexOf('\u0000') >= 0) {
      throw new IllegalArgumentException("file appears to be binary: contains NUL character");
    }
    return new Decoded(text, charset, bomLength);
  }

  private static boolean starts(byte[] bytes, byte... prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if (bytes[index] != prefix[index]) {
        return false;
      }
    }
    return true;
  }

  record Decoded(String text, Charset charset, int bomLength) {}
}
