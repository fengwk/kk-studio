package fun.fengwk.kkstudio.platform.cloudfs.domain;

import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 严格 UTF-8 编码工具，杜绝替换字符产生，对畸变输入与未配对 surrogate 快速失败。 */
public final class StrictUtf8 {

  private StrictUtf8() {}

  /**
   * 严格按照 UTF-8 进行字符编码。
   *
   * @param s 待编码字符串
   * @param errorContext 错误上下文描述
   * @return 严格编码后的 UTF-8 字节数组
   * @throws CloudPathValidationException 遇非法字符或未配对 surrogate 时抛出
   */
  public static byte[] encode(String s, String errorContext) {
    if (s == null) {
      return new byte[0];
    }
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      ByteBuffer buf = encoder.encode(CharBuffer.wrap(s));
      byte[] bytes = new byte[buf.remaining()];
      buf.get(bytes);
      return bytes;
    } catch (CharacterCodingException e) {
      throw new CloudPathValidationException("Invalid UTF-8 encoding in " + errorContext, e);
    }
  }
}
