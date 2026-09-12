package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次扫描得到的本地 skill：稳定的（来源、名称、内容 revision）身份加上指令正文。
 *
 * <p>正文是 SKILL.md 去除 front matter 后的 instruction content；{@code contentRevision} 是 SKILL.md 原始字节的
 * lowercase SHA-256，因此正文变化必然改变 revision，而 revision 相同必然正文相同。
 */
public record DaemonSkill(
    String name, String description, Path baseDirectory, String contentRevision, String body) {

  public DaemonSkill {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description must not be blank");
    }
    baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
    if (!baseDirectory.isAbsolute()) {
      throw new IllegalArgumentException("baseDirectory must be an absolute path");
    }
    DaemonSkillDescriptor.contentRevision(contentRevision);
    body = Objects.requireNonNull(body, "body");
  }

  /** 生成该 scan 结果在给定来源/配置版本下的 READY descriptor。 */
  public DaemonSkillDescriptor descriptor(UUID sourceId, long sourceVersion) {
    return new DaemonSkillDescriptor(
        sourceId, sourceVersion, name, description, baseDirectory.toString(), contentRevision);
  }
}
