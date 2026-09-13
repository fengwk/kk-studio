package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 针对原生 {@link SkillFrontMatterParser} 的单元测试。
 *
 * <p>验证元数据规范：包含普通标量、单/双引号、块标量（| 与 >）、未知扩展字段忽略、 front matter 剥离以及非法格式/非法 UTF-8 的脱敏异常拦截。
 */
class SkillFrontMatterParserTest {

  @TempDir Path tempDir;

  @Test
  void parsesUnquotedScalarsAndStripsFrontMatter() throws IOException {
    // 验证最常见的无引号标量格式，确保 front matter 从正文中彻底剥离且首尾空白正常 trim
    String content =
        """
        ---
        name: simple-skill
        description: A simple test skill description
        ---
        # Heading

        Instruction body line 1.
        """;
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("simple-skill", skill.name());
    assertEquals("A simple test skill description", skill.description());
    assertEquals("# Heading\n\nInstruction body line 1.", skill.body());
  }

  @Test
  void parsesDoubleAndSingleQuotedScalars() throws IOException {
    // 验证包含转义的双引号标量与单引号标量解析
    String content =
        """
        ---
        name: "quoted-skill"
        description: 'Single quoted: ''hello'', "world"!'
        ---
        Body content.
        """;
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("quoted-skill", skill.name());
    assertEquals("Single quoted: 'hello', \"world\"!", skill.description());
    assertEquals("Body content.", skill.body());
  }

  @Test
  void parsesLiteralBlockScalarDescription() throws IOException {
    // 验证 '|' 块标量：保留多行换行与公共缩进剥离
    String content =
        """
        ---
        name: block-literal
        description: |
          Line 1 of description.
          Line 2 of description.

          Line 3 after blank line.
        ---
        Body content.
        """;
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("block-literal", skill.name());
    assertEquals(
        "Line 1 of description.\nLine 2 of description.\n\nLine 3 after blank line.",
        skill.description());
    assertEquals("Body content.", skill.body());
  }

  @Test
  void parsesFoldedBlockScalarDescription() throws IOException {
    // 验证 '>' 折叠块标量：单换行折叠为空格，空行保留段落分隔
    String content =
        """
        ---
        name: block-folded
        description: >
          First paragraph line 1.
          First paragraph line 2.

          Second paragraph.
        ---
        Body content.
        """;
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("block-folded", skill.name());
    assertEquals(
        "First paragraph line 1. First paragraph line 2.\n\nSecond paragraph.",
        skill.description());
  }

  @Test
  void safelyIgnoresUnknownTopLevelFields() throws IOException {
    // 验证包含兼容性与权限等未知扩展字段时可安全解析，不报错且不污染必填属性
    String content =
        """
        ---
        name: extended-skill
        description: Standard description
        compatibility: |
          Compatible with all LLM engines.
          No special runtime required.
        allowed-tools: bash, read, edit
        license: MIT
        ---
        # Instructions
        """;
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("extended-skill", skill.name());
    assertEquals("Standard description", skill.description());
    assertEquals("# Instructions", skill.body());
  }

  @Test
  void rejectsMissingOpeningDelimiter() throws IOException {
    // 缺少起始 '---' 分隔符
    String content = "name: no-opening\ndescription: test\n---\nBody\n";
    writeSkill(tempDir, content);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(error.getMessage().contains("opening delimiter"));
  }

  @Test
  void rejectsMissingClosingDelimiter() throws IOException {
    // 缺少闭合 '---' 分隔符
    String content = "---\nname: unclosed\ndescription: test\n# Body without close\n";
    writeSkill(tempDir, content);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(error.getMessage().contains("closing delimiter"));
  }

  @Test
  void rejectsDuplicateFields() throws IOException {
    // 重复声明 name 字段
    String content =
        """
        ---
        name: duplicate-name
        name: second-name
        description: test
        ---
        Body
        """;
    writeSkill(tempDir, content);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertEquals("duplicate front matter field", error.getMessage());
  }

  @Test
  void rejectsMissingOrBlankRequiredFields() throws IOException {
    // 空白 name
    String contentBlankName = "---\nname: \"   \"\ndescription: test\n---\nBody\n";
    writeSkill(tempDir, contentBlankName);
    IllegalArgumentException errorName =
        assertThrows(
            IllegalArgumentException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(errorName.getMessage().contains("missing non-blank front matter name"));

    // 缺少 description
    String contentMissingDesc = "---\nname: valid-name\n---\nBody\n";
    writeSkill(tempDir, contentMissingDesc);
    IllegalArgumentException errorDesc =
        assertThrows(
            IllegalArgumentException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(errorDesc.getMessage().contains("missing non-blank front matter description"));
  }

  @Test
  void rejectsInvalidUtf8WithoutEchoingContents() throws IOException {
    // 注入非法 UTF-8 字节，确保抛出 sanitized 异常且不泄露二进制内容
    byte[] invalidBytes = new byte[] {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
    Files.write(tempDir.resolve("SKILL.md"), invalidBytes);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(error.getMessage().contains("not valid UTF-8"));
    assertFalse(error.getMessage().contains("\uFFFD"));
  }

  @Test
  void rejectsMalformedEntryInsideFrontMatter() throws IOException {
    // 包含不合法的非缩进且无冒号行
    String content = "---\nname: bad\nmalformed line without colon\ndescription: ok\n---\nBody\n";
    writeSkill(tempDir, content);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(error.getMessage().contains("malformed front matter entry"));
  }

  /** 测试意图：包含 UTF-8 BOM（\uFEFF）与 front matter 内注释（#）能被正常剥离解析。 */
  @Test
  void parsesUtf8WithBomAndComments() throws IOException {
    String content =
        "\uFEFF---\n# Leading comment\nname: bom-skill\n# Mid comment\n\ndescription: Skill with BOM\n---\n# Body\n";
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("bom-skill", skill.name());
    assertEquals("Skill with BOM", skill.description());
    assertEquals("# Body", skill.body());
  }

  /** 测试意图：冒号后无符号的多行缩进延续文本（无 | 或 >）能被正确识别为块标量。 */
  @Test
  void parsesMultilineContinuationScalarWithoutPipe() throws IOException {
    String content =
        """
        ---
        name:
          multiline-name
        description:
          first line
          second line
        ---
        Body content
        """;
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("multiline-name", skill.name());
    assertEquals("first line\nsecond line", skill.description());
    assertEquals("Body content", skill.body());
  }

  /** 测试意图：冒号后空无内容且无缩进延续行时解析为空字符串，触发缺少必填项校验。 */
  @Test
  void rejectsEmptyKeyOrEmptyFieldValue() throws IOException {
    // 冒号后完全为空且无延续行
    String emptyValueContent =
        """
        ---
        name:
        description: valid description
        ---
        Body
        """;
    writeSkill(tempDir, emptyValueContent);
    SkillParseException emptyValError =
        assertThrows(
            SkillParseException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(emptyValError.getMessage().contains("missing non-blank front matter name"));

    // 冒号前 key 为空
    String emptyKeyContent =
        """
        ---
        : empty-key-value
        name: valid
        description: valid
        ---
        Body
        """;
    writeSkill(tempDir, emptyKeyContent);
    SkillParseException error =
        assertThrows(
            SkillParseException.class, () -> SkillFrontMatterParser.parse(tempDir, "test"));
    assertTrue(error.getMessage().contains("empty front matter key"));
  }

  /** 测试意图：双引号标量转义字符（\\r, \\t, \\", \\\\ 以及末尾单个 \\）能被正确反转义。 */
  @Test
  void parsesDoubleQuotedEscapeSequences() throws IOException {
    String content =
        """
        ---
        name: "escaped\\tskill"
        description: "first\\rsecond\\tthird\\\"quote\\\\slash\\a"
        ---
        Body
        """;
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("escaped\tskill", skill.name());
    assertEquals("first\rsecond\tthird\"quote\\slasha", skill.description());
  }

  /** 测试意图：支持 CRLF（\\r\\n）与经典 Mac CR（\\r）换行符的统一拆分。 */
  @Test
  void parsesCrlfAndStandaloneCrLineEndings() throws IOException {
    String content =
        "---\r\nname: crlf-skill\r\ndescription: crlf desc\r\n---\r\nLine 1\rLine 2\r\nLine 3\n";
    writeSkill(tempDir, content);

    DaemonSkill skill = SkillFrontMatterParser.parse(tempDir, "test");
    assertEquals("crlf-skill", skill.name());
    assertEquals("crlf desc", skill.description());
    assertEquals("Line 1\nLine 2\nLine 3", skill.body());
  }

  /** 测试意图：SKILL.md 不存在或不可读时抛出清晰且脱敏的 SkillParseException。 */
  @Test
  void rejectsNonReadableSkillFile() {
    Path missingDir = tempDir.resolve("missing");
    SkillParseException error =
        assertThrows(
            SkillParseException.class,
            () -> SkillFrontMatterParser.parse(missingDir, "source-ctx"));
    assertTrue(error.getMessage().contains("not readable"));
    assertTrue(error.getMessage().contains("source-ctx"));
    assertFalse(error.getMessage().contains(missingDir.toString()));
  }

  private void writeSkill(Path root, String content) throws IOException {
    Files.writeString(root.resolve("SKILL.md"), content);
  }
}
