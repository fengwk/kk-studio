package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.List;
import java.util.Objects;

/**
 * Daemon READY 上报的版本化/类型化能力摘要。
 *
 * <p>只包含可安全上报的短字段：environment metadata 与 skills 摘要；完整 SKILL.md 正文不进入 READY wire。
 */
public record DaemonCapabilities(
    int version, DaemonEnvironmentInfo environment, List<DaemonSkillDescriptor> skills) {

  /** READY capabilities 协议版本；与 {@link DaemonCapabilitiesCodec} 共享。 */
  public static final int VERSION = 1;

  public DaemonCapabilities {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported capabilities version: " + version);
    }
    environment = Objects.requireNonNull(environment, "environment");
    skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
    requireUniqueSkillNames(skills);
  }

  private static void requireUniqueSkillNames(List<DaemonSkillDescriptor> skills) {
    long unique = skills.stream().map(DaemonSkillDescriptor::name).distinct().count();
    if (unique != skills.size()) {
      throw new IllegalArgumentException("duplicate READY skill name");
    }
  }
}
