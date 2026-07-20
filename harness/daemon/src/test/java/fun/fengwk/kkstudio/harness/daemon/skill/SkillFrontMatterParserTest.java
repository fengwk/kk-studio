package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Front matter 解析只认 name/description，并拒绝非法格式。 */
class SkillFrontMatterParserTest {

  @Test
  void parsesNameAndDescriptionAndIgnoresOtherKeys() {
    SkillFrontMatter matter =
        SkillFrontMatterParser.parse(
            "---\nname: dev\ndescription: Developer rules\nallowed-tools: Bash\nhidden: true\n---\n# body\n");
    assertEquals("dev", matter.name());
    assertEquals("Developer rules", matter.description());
  }

  @Test
  void supportsQuotedValues() {
    SkillFrontMatter matter =
        SkillFrontMatterParser.parse("---\nname: \"quoted\"\ndescription: 'text'\n---\n");
    assertEquals("quoted", matter.name());
    assertEquals("text", matter.description());
  }

  @Test
  void rejectsMissingClosingDelimiter() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SkillFrontMatterParser.parse("---\nname: x\ndescription: y\n"));
    assertTrue(error.getMessage().contains("closed"));
  }
}
