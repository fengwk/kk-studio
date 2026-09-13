package fun.fengwk.kkstudio.platform.project.service.impl;

import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 领域边界预校验工具类，使用严格 UTF-8 encoder（REPORT）做字符与字节边界检查，错误消息脱敏且不回显输入正文。 */
public final class ProjectValidationUtils {

  private ProjectValidationUtils() {}

  public static String trimAndValidate(
      String value, String fieldName, int maxLength, boolean required) {
    if (value == null) {
      if (required) {
        throw new AiValidationException(fieldName, fieldName + " must not be blank");
      }
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      if (required) {
        throw new AiValidationException(fieldName, fieldName + " must not be blank");
      }
      return null;
    }
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      encoder.encode(CharBuffer.wrap(trimmed));
    } catch (CharacterCodingException e) {
      throw new AiValidationException(fieldName, fieldName + " contains invalid UTF-8 encoding");
    }
    if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
      throw new AiValidationException(
          fieldName, fieldName + " exceeds maximum allowed length of " + maxLength + " characters");
    }
    return trimmed;
  }

  public static void validateUtf8Bytes(
      String text, String fieldName, int maxBytes, boolean required) {
    if (text == null) {
      if (required) {
        throw new AiValidationException(fieldName, fieldName + " must not be blank");
      }
      return;
    }
    if (required && text.isBlank()) {
      throw new AiValidationException(fieldName, fieldName + " must not be blank");
    }
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer bb;
    try {
      bb = encoder.encode(CharBuffer.wrap(text));
    } catch (CharacterCodingException e) {
      throw new AiValidationException(fieldName, fieldName + " contains invalid UTF-8 encoding");
    }
    if (bb.remaining() > maxBytes) {
      throw new AiValidationException(
          fieldName, fieldName + " exceeds maximum allowed UTF-8 size of " + maxBytes + " bytes");
    }
  }
}
