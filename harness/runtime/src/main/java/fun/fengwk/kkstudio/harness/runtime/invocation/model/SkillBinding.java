package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;

import java.util.Objects;
import java.util.UUID;

/**
 * 冻结到一次 Model invocation 中的不可变 skill 事实。
 *
 * <p>身份是 {@code (sourceEnvironmentId, sourceId, name)} 加 {@code contentRevision}；{@code
 * description} 与 {@code baseDirectory} 是规划与执行都要用的描述事实。body 被刻意排除在外：正文由 {@code skill.load} 按精确
 * revision 取回， 不进入 durable 请求。冻结事实里没有 workdir —— skill 按身份与来源定位，不依赖会话目录。
 *
 * <p>全部字段都是必填：缺少 sourceId 或 revision 的旧形状被明确拒绝，不做 tolerant 解码，因此旧请求不会被静默解释成“当前版本”。
 */
public record SkillBinding(
    EnvironmentId sourceEnvironmentId,
    UUID sourceId,
    String name,
    String description,
    String baseDirectory,
    String contentRevision) {

  public SkillBinding {
    sourceEnvironmentId = Objects.requireNonNull(sourceEnvironmentId, "sourceEnvironmentId");
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
    name = DaemonSkillDescriptor.canonicalName(name);
    description = DaemonSkillDescriptor.canonicalDescription(description);
    baseDirectory = DaemonSkillDescriptor.canonicalBaseDirectory(baseDirectory);
    contentRevision = DaemonSkillDescriptor.contentRevision(contentRevision);
  }
}
