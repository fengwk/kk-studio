package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/**
 * Skill registry 测试基座：在调用者给定的隔离目录下打开持久 registry，并可按 PATH 来源发布已存在的目录。
 *
 * <p>生产路径没有“直接由目录列表构造 registry”的入口（来源必须经过受管配置与发布校验），因此测试也走同一条发布路径，才能证明发布 语义、revision 计算与 READY
 * 快照本身。数据目录必须由调用者提供隔离位置（通常是 {@code @TempDir} 或测试私有目录），避免并行测试 共享持久状态。
 */
public final class DaemonSkillTestSupport {

  /** 测试来源 ID 固定，便于断言快照归属。 */
  public static final UUID SOURCE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private DaemonSkillTestSupport() {}

  /** 打开 {@code dataDir} 上的空 registry（不发布任何来源）；{@code dataDir} 必须是绝对路径。 */
  public static DaemonSkillRegistry open(Path dataDir) {
    return DaemonSkillRegistry.open(dataDir, dataDir.getParent());
  }

  /** 打开 registry 并发布一个 PATH 来源，返回 registry 与来源快照。 */
  public static Published publish(Path dataDir, Path skillDirectory, long sourceVersion) {
    DaemonSkillRegistry registry = open(dataDir);
    DaemonSkillSourceSnapshot snapshot =
        registry.refresh(pathConfig(skillDirectory, sourceVersion));
    return new Published(registry, snapshot);
  }

  /** 构造指向既有目录的 PATH 来源配置；集合版本与行版本取同一序号，符合“每次配置变更同时前进两者”的常规用法。 */
  public static DaemonSkillSourceConfig pathConfig(Path skillDirectory, long sourceVersion) {
    return pathConfig(SOURCE_ID, skillDirectory, sourceVersion, sourceVersion);
  }

  /** 构造指定来源的 PATH 来源配置，显式区分行版本与全局来源集合版本。 */
  public static DaemonSkillSourceConfig pathConfig(
      UUID sourceId, Path skillDirectory, long sourceVersion, long sourceSetVersion) {
    return DaemonSkillSourceConfig.path(
        sourceId,
        sourceVersion,
        sourceSetVersion,
        skillDirectory.toString(),
        false,
        Set.of(sourceId));
  }

  /** 已打开的 registry 与其首个来源快照。 */
  public record Published(DaemonSkillRegistry registry, DaemonSkillSourceSnapshot snapshot) {}
}
