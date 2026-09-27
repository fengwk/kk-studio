package fun.fengwk.kkstudio.project.service.impl;

import fun.fengwk.kkstudio.project.error.ProjectValidationException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Project 领域边界预校验工具：使用严格 UTF-8 encoder（REPORT）做字符与字节边界检查，错误消息脱敏且不回显输入正文。
 *
 * <p>列宽上限与目标 DDL 一致：title 256、Agent/Environment 自然名 64、description 64 KiB、Activity/Evidence 正文 1
 * MiB。
 */
public final class ProjectValidationUtils {

  /** {@code varchar(256)} 的字符上限。 */
  public static final int MAX_TITLE_LENGTH = 256;

  /** {@code agent_definition.name}/{@code environment.name} 的列宽上限。 */
  public static final int MAX_CANONICAL_NAME_LENGTH = 64;

  /** {@code project.description} 的字节上限（64 KiB）。 */
  public static final int MAX_DESCRIPTION_BYTES = 65536;

  /** {@code project_issue_activity.body}/{@code project_issue.description} 的字节上限（1 MiB）。 */
  public static final int MAX_LARGE_TEXT_BYTES = 1048576;

  private ProjectValidationUtils() {}

  /** 显示名：非空白、无首尾空白、不超过 {@code maxLength} 个码点。 */
  public static String requireDisplayName(String value, String field, int maxLength) {
    if (value == null) {
      throw new ProjectValidationException(field, field + " must not be blank");
    }
    if (value.isBlank()) {
      throw new ProjectValidationException(field, field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new ProjectValidationException(field, field + " must not have surrounding whitespace");
    }
    if (value.codePointCount(0, value.length()) > maxLength) {
      throw new ProjectValidationException(
          field, field + " exceeds maximum allowed length of " + maxLength + " characters");
    }
    return value;
  }

  /** Agent/Environment 规范名：显示名规则，且不含 {@code '/'}（避免与路径/工具名混淆）。 */
  public static String requireCanonicalName(String value, String field) {
    String name = requireDisplayName(value, field, MAX_CANONICAL_NAME_LENGTH);
    if (name.indexOf('/') >= 0) {
      throw new ProjectValidationException(field, field + " must not contain '/'");
    }
    return name;
  }

  /** 正文：可空；非空时必须是严格 UTF-8 且不超过字节上限，错误消息不回显正文。 */
  public static String optionalUtf8Text(String value, String field, int maxBytes) {
    if (value == null) {
      return null;
    }
    CharsetEncoder encoder =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer encoded;
    try {
      encoded = encoder.encode(CharBuffer.wrap(value));
    } catch (CharacterCodingException error) {
      throw new ProjectValidationException(field, field + " contains invalid UTF-8 encoding");
    }
    if (encoded.remaining() > maxBytes) {
      throw new ProjectValidationException(
          field, field + " exceeds maximum allowed UTF-8 size of " + maxBytes + " bytes");
    }
    return value;
  }

  /** 正文：不可空且不足空白。 */
  public static String requireUtf8Text(String value, String field, int maxBytes) {
    if (value == null || value.isBlank()) {
      throw new ProjectValidationException(field, field + " must not be blank");
    }
    return optionalUtf8Text(value, field, maxBytes);
  }

  /** 执行领域校验动作，把确定性领域拒绝翻译为平台校验错误。 */
  public static void validate(String resource, Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException error) {
      throw validation(resource, error);
    }
  }

  /** 执行领域校验动作并返回结果，把确定性领域拒绝翻译为平台校验错误。 */
  public static <T> T resolve(String resource, Supplier<T> action) {
    try {
      return action.get();
    } catch (IllegalArgumentException error) {
      throw validation(resource, error);
    }
  }

  /** 把领域层确定性拒绝（{@link IllegalArgumentException}）翻译为平台校验错误，避免领域拒绝泄漏为内部错误。 */
  public static ProjectValidationException validation(
      String resource, IllegalArgumentException error) {
    return new ProjectValidationException(resource, error.getMessage());
  }

  /** 请求正文指纹：SHA-256 小写十六进制，用于 Activity 的幂等去重。 */
  public static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }
}
