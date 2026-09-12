package fun.fengwk.kkstudio.harness.builtin.skill;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;

import java.util.Objects;
import java.util.UUID;

/**
 * 当前 Thread Agent 已选中的 Skill 的冻结身份。
 *
 * <p>{@code sourceEnvironmentId} 是提供正文的环境路由身份（必填：没有来源的 Skill 不可加载），{@code sourceId} 是 Skill 来源
 * 身份，{@code contentRevision} 是冻结的内容 revision。三者共同保证 {@code load_skill} 取回的是选择当时的版本，而不是同名的新版本。
 *
 * @param sourceEnvironmentId 提供正文的 Environment 身份，必填
 * @param sourceId Skill 来源身份，必填
 * @param name skill 标识名，非空
 * @param description 冻结描述，必填
 * @param baseDirectory Skill 所在宿主目录，必填
 * @param contentRevision 冻结的内容 revision，必填
 */
public record SelectedSkill(
    EnvironmentId sourceEnvironmentId,
    UUID sourceId,
    String name,
    String description,
    String baseDirectory,
    String contentRevision) {

  public SelectedSkill {
    sourceEnvironmentId = Objects.requireNonNull(sourceEnvironmentId, "sourceEnvironmentId");
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
    name = DaemonSkillDescriptor.canonicalName(name);
    description = DaemonSkillDescriptor.canonicalDescription(description);
    baseDirectory = DaemonSkillDescriptor.canonicalBaseDirectory(baseDirectory);
    contentRevision = DaemonSkillDescriptor.contentRevision(contentRevision);
  }
}
