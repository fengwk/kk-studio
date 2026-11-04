package fun.fengwk.kkstudio.platform.catalog.skill.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillManifestScanner;

class SkillManifestParsingTest {
  @Test
  void parsesMultilineDescriptionAndChecksDirectoryName() {
    // Archive entries must use the same frontmatter rules as the existing scanner.
    var manifest =
        SkillManifestScanner.parseFrontmatter(
            "example", "---\nname: example\ndescription: |-\n  first\n  second\n---\n");
    assertEquals("example", manifest.name());
    assertEquals("first\nsecond", manifest.description());
    assertThrows(
        SkillGitException.class,
        () ->
            SkillManifestScanner.parseFrontmatter(
                "other", "---\nname: example\ndescription: text\n---\n"));
  }
}
