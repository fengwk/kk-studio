package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次成功扫描后单个 Skill 来源的不可变快照。
 *
 * <p>{@code sourceRevision} 是该来源本次发布的应用 revision：PATH 来源等于其内容聚合 revision，GIT 来源是解析并固定的 commit。
 * {@code sourceVersion} 对齐扫描时的配置版本，Platform 据此拒绝被新配置超越的旧报告。descriptor 只包含该来源发现的 skill；
 * 诊断是该来源的有界发现说明。
 */
public record DaemonSkillSourceSnapshot(
    UUID sourceId,
    long sourceVersion,
    String sourceRevision,
    List<DaemonSkillDescriptor> skills,
    List<DaemonSkillDiagnostic> diagnostics) {

  /** 单来源诊断条数上限。 */
  public static final int MAX_DIAGNOSTICS = 256;

  public DaemonSkillSourceSnapshot {
    sourceId = Objects.requireNonNull(sourceId, "sourceId");
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("sourceVersion must not be negative");
    }
    sourceRevision = sourceRevision(sourceRevision);
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    if (diagnostics.size() > MAX_DIAGNOSTICS) {
      throw new IllegalArgumentException(
          "diagnostics must not exceed " + MAX_DIAGNOSTICS + " entries");
    }
    Set<String> names = new HashSet<>();
    for (DaemonSkillDescriptor skill : skills) {
      Objects.requireNonNull(skill, "skills[]");
      if (!skill.sourceId().equals(sourceId)) {
        throw new IllegalArgumentException("skill descriptor sourceId must match the snapshot");
      }
      if (skill.sourceVersion() != sourceVersion) {
        throw new IllegalArgumentException(
            "skill descriptor sourceVersion must match the snapshot");
      }
      if (!names.add(skill.name())) {
        throw new IllegalArgumentException("duplicate skill name within source: " + skill.name());
      }
    }
  }

  private static String sourceRevision(String value) {
    if (!DaemonSkillSourceConfig.isCommitId(value)) {
      throw new IllegalArgumentException(
          "sourceRevision must be a lowercase 40- or 64-hex revision");
    }
    return value;
  }
}
