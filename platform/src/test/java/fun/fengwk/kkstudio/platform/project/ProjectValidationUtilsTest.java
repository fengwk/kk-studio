package fun.fengwk.kkstudio.platform.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.project.service.impl.ProjectValidationUtils;

/** 验证字段校验工具类的 UTF-8 字节长度、未配对代理项（Unpaired Surrogate）、空值处理及敏感信息不回显策略。 */
class ProjectValidationUtilsTest {

  @Test
  void testTrimAndValidateSuccess() {
    assertNull(ProjectValidationUtils.trimAndValidate(null, "field", 100, false));
    assertNull(ProjectValidationUtils.trimAndValidate("   ", "field", 100, false));
    assertEquals("test", ProjectValidationUtils.trimAndValidate("  test  ", "field", 100, true));
    assertEquals("hello", ProjectValidationUtils.trimAndValidate("hello", "field", 5, true));
  }

  @Test
  void testTrimAndValidateRequiredChecks() {
    AiValidationException ex1 =
        assertThrows(
            AiValidationException.class,
            () -> ProjectValidationUtils.trimAndValidate(null, "username", 100, true));
    assertEquals("username must not be blank", ex1.getMessage());

    AiValidationException ex2 =
        assertThrows(
            AiValidationException.class,
            () -> ProjectValidationUtils.trimAndValidate("   ", "secretField", 100, true));
    assertEquals("secretField must not be blank", ex2.getMessage());
    assertFalse(ex2.getMessage().contains("   "));
  }

  @Test
  void testTrimAndValidateByteLengthExceeded() {
    String longStr = "a".repeat(101);
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> ProjectValidationUtils.trimAndValidate(longStr, "payload", 100, true));
    assertEquals("payload exceeds maximum allowed length of 100 characters", ex.getMessage());
    // 确保不回显长内容
    assertFalse(ex.getMessage().contains(longStr));
  }

  @Test
  void testValidateUtf8BytesSuccess() {
    ProjectValidationUtils.validateUtf8Bytes(null, "field", 100, false);
    ProjectValidationUtils.validateUtf8Bytes("   ", "field", 100, false);
    ProjectValidationUtils.validateUtf8Bytes("hello", "field", 5, true);
    // 中文：每个汉字 3 字节，共 6 字节
    ProjectValidationUtils.validateUtf8Bytes("你好", "field", 6, true);
    // Emoji: 4 字节
    ProjectValidationUtils.validateUtf8Bytes("🚀", "field", 4, true);
  }

  @Test
  void testValidateUtf8BytesRequiredChecks() {
    assertThrows(
        AiValidationException.class,
        () -> ProjectValidationUtils.validateUtf8Bytes(null, "field", 100, true));
    assertThrows(
        AiValidationException.class,
        () -> ProjectValidationUtils.validateUtf8Bytes("   ", "field", 100, true));
  }

  @Test
  void testValidateUtf8BytesLengthExceeded() {
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> ProjectValidationUtils.validateUtf8Bytes("你好a", "field", 6, true));
    assertEquals("field exceeds maximum allowed UTF-8 size of 6 bytes", ex.getMessage());
  }

  @Test
  void testUnpairedSurrogatesRejected() {
    // 高代理项未配对
    String highSurrogateOnly = "prefix\uD800suffix";
    AiValidationException ex1 =
        assertThrows(
            AiValidationException.class,
            () ->
                ProjectValidationUtils.validateUtf8Bytes(highSurrogateOnly, "content", 100, true));
    assertEquals("content contains invalid UTF-8 encoding", ex1.getMessage());
    assertFalse(ex1.getMessage().contains("prefix"));

    // 低代理项未配对
    String lowSurrogateOnly = "prefix\uDC00suffix";
    AiValidationException ex2 =
        assertThrows(
            AiValidationException.class,
            () -> ProjectValidationUtils.trimAndValidate(lowSurrogateOnly, "field", 100, true));
    assertEquals("field contains invalid UTF-8 encoding", ex2.getMessage());
  }

  @Test
  void testTrimAndValidateEmojiCodePointCount() {
    // 每个 Emoji 🚀 占 2 个 char (高低代理项)，但属于 1 个 Unicode 代码点 (code point)
    String fiveEmojis = "🚀".repeat(5);
    assertEquals(10, fiveEmojis.length());
    assertEquals(5, fiveEmojis.codePointCount(0, fiveEmojis.length()));
    assertEquals(
        fiveEmojis, ProjectValidationUtils.trimAndValidate(fiveEmojis, "emojiField", 5, true));

    // 6 个 Emoji 超过 5 个字符上限 (PostgreSQL varchar(5))
    String sixEmojis = "🚀".repeat(6);
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () -> ProjectValidationUtils.trimAndValidate(sixEmojis, "emojiField", 5, true));
    assertEquals("emojiField exceeds maximum allowed length of 5 characters", ex.getMessage());
  }

  @Test
  void testValidateUtf8BytesOptionalOversizedBlankRejected() {
    // required = false 时，如果传入非 null 空白且超过字节限制（如 70000 字节空格），必须被拒绝
    String oversizedSpaces = " ".repeat(70000);
    AiValidationException ex =
        assertThrows(
            AiValidationException.class,
            () ->
                ProjectValidationUtils.validateUtf8Bytes(
                    oversizedSpaces, "description", 65536, false));
    assertEquals("description exceeds maximum allowed UTF-8 size of 65536 bytes", ex.getMessage());

    // null 在 required = false 时合法通过
    ProjectValidationUtils.validateUtf8Bytes(null, "description", 65536, false);
  }

  @Test
  void testLargeBoundaries() {
    // 16 KiB 边界 (16384 bytes)
    String exact16KiB = "a".repeat(16384);
    ProjectValidationUtils.validateUtf8Bytes(exact16KiB, "waiting_reason", 16384, true);
    assertThrows(
        AiValidationException.class,
        () ->
            ProjectValidationUtils.validateUtf8Bytes(
                exact16KiB + "b", "waiting_reason", 16384, true));

    // 64 KiB 边界 (65536 bytes)
    String exact64KiB = "x".repeat(65536);
    ProjectValidationUtils.validateUtf8Bytes(exact64KiB, "description", 65536, true);
    assertThrows(
        AiValidationException.class,
        () ->
            ProjectValidationUtils.validateUtf8Bytes(exact64KiB + "y", "description", 65536, true));

    // 1 MiB 边界 (1048576 bytes)
    String exact1MiB = "z".repeat(1048576);
    ProjectValidationUtils.validateUtf8Bytes(exact1MiB, "payload", 1048576, true);
    assertThrows(
        AiValidationException.class,
        () -> ProjectValidationUtils.validateUtf8Bytes(exact1MiB + "!", "payload", 1048576, true));
  }
}
