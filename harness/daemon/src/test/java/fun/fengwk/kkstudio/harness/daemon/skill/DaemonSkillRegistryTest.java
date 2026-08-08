package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Skill 目录发现：LangChain4j front matter 解析、同名冲突与指令正文加载。 */
class DaemonSkillRegistryTest {

  @TempDir Path tempDir;

  @Test
  void discoversChildSkillsAndLoadsInstructionContent() throws IOException {
    Path skillA = tempDir.resolve("alpha");
    Files.createDirectories(skillA);
    String body = "---\nname: alpha\ndescription: Alpha skill\n---\n# Alpha\n\nbody line\n";
    Files.writeString(skillA.resolve("SKILL.md"), body);

    DaemonSkillRegistry registry = DaemonSkillRegistry.discover(List.of(tempDir));

    assertEquals(1, registry.descriptors().size());
    DaemonSkillDescriptor descriptor = registry.descriptors().iterator().next();
    assertEquals("alpha", descriptor.name());
    assertEquals("Alpha skill", descriptor.description());
    // 指令正文 = SKILL.md 去除 front matter。
    assertEquals("# Alpha\n\nbody line", registry.loadBody("alpha").orElseThrow());
  }

  @Test
  void discoversRootSkillMdInConfiguredDir() throws IOException {
    String body = "---\nname: root-skill\ndescription: Root\n---\n# Root\n";
    Files.writeString(tempDir.resolve("SKILL.md"), body);

    DaemonSkillRegistry registry = DaemonSkillRegistry.discover(List.of(tempDir));

    assertEquals("root-skill", registry.descriptors().iterator().next().name());
    assertEquals("# Root", registry.loadBody("root-skill").orElseThrow());
  }

  @Test
  void rejectsMissingFrontMatterFields() throws IOException {
    Path skill = tempDir.resolve("broken");
    Files.createDirectories(skill);
    Files.writeString(skill.resolve("SKILL.md"), "---\nname: broken\n---\n");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> DaemonSkillRegistry.discover(List.of(tempDir)));
    assertTrue(error.getMessage().contains("description"));
  }

  @Test
  void rejectsDuplicateSkillNamesAcrossDirs(@TempDir Path otherDir) throws IOException {
    writeSkill(tempDir, "shared", "first");
    writeSkill(otherDir, "shared", "second");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> DaemonSkillRegistry.discover(List.of(tempDir, otherDir)));
    assertTrue(error.getMessage().contains("duplicate skill name: shared"));
  }

  @Test
  void rejectsMissingSkillDir() {
    Path missing = tempDir.resolve("does-not-exist");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> DaemonSkillRegistry.discover(List.of(missing)));
    assertTrue(error.getMessage().contains("existing directory"));
  }

  private void writeSkill(Path root, String name, String description) throws IOException {
    Path skill = root.resolve(name);
    Files.createDirectories(skill);
    Files.writeString(
        skill.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n");
  }
}
