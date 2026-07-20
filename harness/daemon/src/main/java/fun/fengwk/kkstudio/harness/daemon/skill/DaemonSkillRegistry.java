package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 从 CLI 配置的 skill 目录发现并登记本地 Skills。
 *
 * <p>每个 skill 目录的直接子目录若含 {@code SKILL.md} 则登记；skill 目录本身含 {@code SKILL.md} 也登记。同名 skill
 * 在启动时拒绝。capabilities 仅暴露 name/description；{@link #loadBody(String)} 返回完整正文。
 */
public final class DaemonSkillRegistry {

  private final Map<String, DaemonSkill> skills;

  private DaemonSkillRegistry(Map<String, DaemonSkill> skills) {
    this.skills = Map.copyOf(skills);
  }

  /** 空 registry，用于无 skill 目录或测试。 */
  public static DaemonSkillRegistry empty() {
    return new DaemonSkillRegistry(Map.of());
  }

  /** 从配置的 skill 目录发现 skills。任一目录不存在或不是目录、任一 SKILL.md 元数据非法、或 name 冲突时失败。 */
  public static DaemonSkillRegistry discover(List<Path> skillDirs) {
    Objects.requireNonNull(skillDirs, "skillDirs");
    Map<String, DaemonSkill> discovered = new LinkedHashMap<>();
    for (Path skillDir : skillDirs) {
      Path absolute =
          Objects.requireNonNull(skillDir, "skillDirs element").toAbsolutePath().normalize();
      if (!Files.isDirectory(absolute)) {
        throw new IllegalArgumentException("skill-dir must be an existing directory: " + absolute);
      }
      Path rootSkill = absolute.resolve("SKILL.md");
      if (Files.isRegularFile(rootSkill)) {
        register(discovered, loadSkill(rootSkill));
      }
      try (Stream<Path> children = Files.list(absolute)) {
        List<Path> ordered = children.filter(Files::isDirectory).sorted().toList();
        for (Path child : ordered) {
          Path skillMd = child.resolve("SKILL.md");
          if (Files.isRegularFile(skillMd)) {
            register(discovered, loadSkill(skillMd));
          }
        }
      } catch (IOException error) {
        throw new IllegalArgumentException("cannot list skill-dir: " + absolute, error);
      }
    }
    return new DaemonSkillRegistry(discovered);
  }

  public synchronized Collection<DaemonSkillDescriptor> descriptors() {
    List<DaemonSkillDescriptor> result = new ArrayList<>(skills.size());
    for (DaemonSkill skill : skills.values()) {
      result.add(skill.descriptor());
    }
    return List.copyOf(result);
  }

  public Optional<DaemonSkill> find(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(skills.get(name));
  }

  /** 返回完整 SKILL.md 正文；未知 name 时 empty。 */
  public Optional<String> loadBody(String name) {
    return find(name).map(DaemonSkill::body);
  }

  private static void register(Map<String, DaemonSkill> discovered, DaemonSkill skill) {
    DaemonSkill previous = discovered.putIfAbsent(skill.name(), skill);
    if (previous != null) {
      throw new IllegalArgumentException("duplicate skill name: " + skill.name());
    }
  }

  private static DaemonSkill loadSkill(Path skillMd) {
    String body;
    try {
      body = Files.readString(skillMd, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalArgumentException("cannot read SKILL.md: " + skillMd, error);
    }
    try {
      SkillFrontMatter matter = SkillFrontMatterParser.parse(body);
      return new DaemonSkill(matter.name(), matter.description(), body);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(
          "invalid SKILL.md metadata at " + skillMd + ": " + error.getMessage(), error);
    }
  }
}
