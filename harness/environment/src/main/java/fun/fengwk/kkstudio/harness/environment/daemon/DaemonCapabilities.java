package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Daemon READY 上报的版本化/类型化能力摘要。
 *
 * <p>只包含可安全上报的短字段：environment metadata 与按来源分组的 skill 描述；完整 SKILL.md 正文不进入 READY wire。 每个来源的
 * descriptor 只能属于该来源，来源 ID 全局唯一，且 skill 名称必须跨全部来源唯一 —— 同名冲突属于发现冲突， 不能靠优先级隐式覆盖。
 */
public record DaemonCapabilities(
    int version, DaemonEnvironmentInfo environment, List<DaemonSkillSourceSnapshot> skillSources) {

  /** READY capabilities 协议版本；与 {@link DaemonCapabilitiesCodec} 共享。 */
  public static final int VERSION = 2;

  /** 单个 payload 内的来源数上限。 */
  public static final int MAX_SOURCES = 512;

  /** 单个 payload 内的 skill 描述总数上限，防止 READY 帧无界放大。 */
  public static final int MAX_SKILLS = 4096;

  public DaemonCapabilities {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported capabilities version: " + version);
    }
    environment = Objects.requireNonNull(environment, "environment");
    skillSources = List.copyOf(Objects.requireNonNull(skillSources, "skillSources"));
    // 构造与解码必须同构：上限只在解码侧检查会让本地编码产生对端必然拒绝的 payload。
    if (skillSources.size() > MAX_SOURCES) {
      throw new IllegalArgumentException(
          "skillSources must not exceed " + MAX_SOURCES + " entries");
    }
    int skillCount = 0;
    for (DaemonSkillSourceSnapshot source : skillSources) {
      skillCount += Objects.requireNonNull(source, "skillSources[]").skills().size();
    }
    if (skillCount > MAX_SKILLS) {
      throw new IllegalArgumentException(
          "skillSources must not exceed " + MAX_SKILLS + " skills in total");
    }
    requireUniqueSourceIds(skillSources);
    requireGloballyUniqueSkillNames(skillSources);
  }

  /** 展平全部来源的 skill 描述；顺序稳定（来源顺序内保持来源内顺序）。 */
  public List<DaemonSkillDescriptor> flattenSkills() {
    List<DaemonSkillDescriptor> result = new ArrayList<>();
    for (DaemonSkillSourceSnapshot source : skillSources) {
      result.addAll(source.skills());
    }
    return List.copyOf(result);
  }

  private static void requireUniqueSourceIds(List<DaemonSkillSourceSnapshot> skillSources) {
    Set<UUID> sourceIds = new HashSet<>();
    for (DaemonSkillSourceSnapshot source : skillSources) {
      Objects.requireNonNull(source, "skillSources[]");
      if (!sourceIds.add(source.sourceId())) {
        throw new IllegalArgumentException("duplicate READY skill source: " + source.sourceId());
      }
    }
  }

  private static void requireGloballyUniqueSkillNames(
      List<DaemonSkillSourceSnapshot> skillSources) {
    Map<String, UUID> owners = new LinkedHashMap<>();
    for (DaemonSkillSourceSnapshot source : skillSources) {
      for (DaemonSkillDescriptor skill : source.skills()) {
        UUID previous = owners.putIfAbsent(skill.name(), source.sourceId());
        if (previous != null) {
          throw new IllegalArgumentException(
              "duplicate READY skill name across sources: " + skill.name());
        }
      }
    }
  }
}
