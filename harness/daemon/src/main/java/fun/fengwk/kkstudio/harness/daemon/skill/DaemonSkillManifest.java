package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Daemon 本地 Skill 目录的持久 manifest。
 *
 * <p>{@code sourceSetVersion} 与 {@code activeSourceIds} 一起描述<strong>来源集合</strong>这一全局事实：同一个集合版本只对应
 * 一个集合，集合内容变化必须前进版本。因此延迟到达的旧集合快照既不能靠更小的版本号、也不能靠同一版本下的更窄集合裁剪掉更新的来源。
 *
 * <p>{@code sources} 是当前生效的来源快照（重启即恢复并原样进入 READY）；{@code retained} 是仍然可按精确 {@code (sourceId, name,
 * revision)} 加载的不可变历史记录。正文本身不进入 manifest：它按内容 revision 存放在不可变 blob 目录，并先于 manifest 提交，因此成功发布的
 * manifest 始终指向完整正文。
 *
 * <p>{@code retained} 必须覆盖当前快照中的每个 descriptor，且 {@code baseDirectory} 必须与当时发布的目录一致；否则 READY
 * 会广告一个立刻无法加载或 指向错误目录的 skill。身份唯一性、跨来源名称唯一性、{@code sources ⊆ activeSourceIds}
 * 与上述交叉不变量都在构造期强制，因此损坏或自相矛盾的 manifest 只会显式失败，绝不会变成半可用的目录。
 */
record DaemonSkillManifest(
    int version,
    long sourceSetVersion,
    List<UUID> activeSourceIds,
    List<DaemonSkillSourceSnapshot> sources,
    List<RetainedSkill> retained) {

  /** manifest 格式版本。 */
  static final int VERSION = 1;

  DaemonSkillManifest {
    if (version != VERSION) {
      throw new IllegalArgumentException("unsupported manifest version: " + version);
    }
    if (sourceSetVersion < 0) {
      throw new IllegalArgumentException("sourceSetVersion must not be negative");
    }
    activeSourceIds = canonicalSourceIds(activeSourceIds);
    sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    retained = List.copyOf(Objects.requireNonNull(retained, "retained"));
    requireReadyInventoryFits(activeSourceIds, sources);
    requireUniqueSourceIds(sources);
    requireGloballyUniqueSkillNames(sources);
    requireUniqueRetainedIdentities(retained);
    requireSourcesAreActive(sources, activeSourceIds);
    requireCurrentDescriptorsAreRetained(sources, retained);
  }

  static DaemonSkillManifest empty() {
    return new DaemonSkillManifest(VERSION, 0L, List.of(), List.of(), List.of());
  }

  /** 持久快照必须能原样进入下一次 READY；损坏清单不能把失败推迟到握手阶段。 */
  private static void requireReadyInventoryFits(
      List<UUID> activeSourceIds, List<DaemonSkillSourceSnapshot> sources) {
    if (activeSourceIds.size() > DaemonCapabilities.MAX_SOURCES
        || sources.size() > DaemonCapabilities.MAX_SOURCES) {
      throw new IllegalArgumentException(
          "skill source count exceeds the READY protocol limit of "
              + DaemonCapabilities.MAX_SOURCES);
    }
    int skillCount = 0;
    for (DaemonSkillSourceSnapshot source : sources) {
      skillCount += source.skills().size();
      if (skillCount > DaemonCapabilities.MAX_SKILLS) {
        throw new IllegalArgumentException(
            "skill count exceeds the READY protocol limit of " + DaemonCapabilities.MAX_SKILLS);
      }
    }
  }

  /** 来源集合的 canonical 形式：去重、按 UUID 升序；重复元素属于自相矛盾的清单。 */
  private static List<UUID> canonicalSourceIds(List<UUID> activeSourceIds) {
    Objects.requireNonNull(activeSourceIds, "activeSourceIds");
    List<UUID> result = List.copyOf(activeSourceIds);
    Set<UUID> unique = new HashSet<>(result);
    if (unique.size() != result.size()) {
      throw new IllegalArgumentException("activeSourceIds must not contain duplicates");
    }
    result = result.stream().sorted().toList();
    return result;
  }

  /** 来源 ID 全局唯一：同一份 manifest 不允许某个来源出现两次。 */
  private static void requireUniqueSourceIds(List<DaemonSkillSourceSnapshot> sources) {
    Set<UUID> sourceIds = new HashSet<>();
    for (DaemonSkillSourceSnapshot snapshot : sources) {
      if (!sourceIds.add(snapshot.sourceId())) {
        throw new IllegalArgumentException(
            "duplicate manifest skill source: " + snapshot.sourceId());
      }
    }
  }

  /** skill 名跨来源唯一：READY 与 load 都按名称定位，重复名称使目标不可判定。 */
  private static void requireGloballyUniqueSkillNames(List<DaemonSkillSourceSnapshot> sources) {
    Map<String, UUID> owners = new HashMap<>();
    for (DaemonSkillSourceSnapshot snapshot : sources) {
      for (DaemonSkillDescriptor skill : snapshot.skills()) {
        UUID previous = owners.putIfAbsent(skill.name(), snapshot.sourceId());
        if (previous != null) {
          throw new IllegalArgumentException("duplicate manifest skill name: " + skill.name());
        }
      }
    }
  }

  /** 保留记录的唯一身份是 {@code (sourceId, name, revision)}；重复身份会让历史索引无法确定正文归属。 */
  private static void requireUniqueRetainedIdentities(List<RetainedSkill> retained) {
    Set<String> identities = new HashSet<>();
    for (RetainedSkill record : retained) {
      if (!identities.add(retentionKey(record.sourceId(), record.name(), record.revision()))) {
        throw new IllegalArgumentException("duplicate retained skill identity: " + record.name());
      }
    }
  }

  /** 已发布的来源必须属于当前生效集合，否则它会在下一次发布时被裁剪，却仍然是 READY 的一部分。 */
  private static void requireSourcesAreActive(
      List<DaemonSkillSourceSnapshot> sources, List<UUID> activeSourceIds) {
    Set<UUID> active = new HashSet<>(activeSourceIds);
    for (DaemonSkillSourceSnapshot snapshot : sources) {
      if (!active.contains(snapshot.sourceId())) {
        throw new IllegalArgumentException(
            "published skill source must be active: " + snapshot.sourceId());
      }
    }
  }

  /**
   * 交叉不变量：当前快照中的每个 descriptor 都必须有与之完全一致的保留记录（含 {@code baseDirectory}）。
   *
   * <p>缺少记录的当前 descriptor 会被 READY 广告出去，却立刻以 {@code RESOURCE_CHANGED} 拒绝加载；{@code baseDirectory}
   * 不一致则会把模型指向错误的宿主目录。两者都只能在裁剪逻辑破坏“当前优先于历史”时产生，因此必须在构造/解码边界显式失败。
   */
  private static void requireCurrentDescriptorsAreRetained(
      List<DaemonSkillSourceSnapshot> sources, List<RetainedSkill> retained) {
    Map<String, RetainedSkill> retainedByKey = new HashMap<>();
    for (RetainedSkill record : retained) {
      retainedByKey.put(retentionKey(record.sourceId(), record.name(), record.revision()), record);
    }
    for (DaemonSkillSourceSnapshot snapshot : sources) {
      for (DaemonSkillDescriptor skill : snapshot.skills()) {
        RetainedSkill record =
            retainedByKey.get(
                retentionKey(snapshot.sourceId(), skill.name(), skill.contentRevision()));
        if (record == null) {
          throw new IllegalArgumentException(
              "current skill descriptor must have a retained record: " + skill.name());
        }
        if (!skill.baseDirectory().equals(record.baseDirectory())) {
          throw new IllegalArgumentException(
              "retained baseDirectory must match the current skill descriptor: " + skill.name());
        }
      }
    }
  }

  /** 保留记录的唯一键：{@code (sourceId, name, revision)} 的 canonical 文本。 */
  static String retentionKey(UUID sourceId, String name, String revision) {
    return sourceId + "\u0000" + name + "\u0000" + revision;
  }

  /** 一条仍然可按 revision 加载的不可变 skill 记录；正文由 revision 对应的 blob 提供。 */
  record RetainedSkill(UUID sourceId, String name, String revision, String baseDirectory) {

    RetainedSkill {
      sourceId = Objects.requireNonNull(sourceId, "sourceId");
      name = DaemonSkillDescriptor.canonicalName(name);
      revision = DaemonSkillDescriptor.contentRevision(revision);
      baseDirectory = DaemonSkillDescriptor.canonicalBaseDirectory(baseDirectory);
    }
  }
}
