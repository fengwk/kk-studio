package fun.fengwk.kkstudio.platform.environment.skill.repo;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;

import java.util.List;
import java.util.UUID;

/**
 * Skill 来源配置与持久 inventory 的仓库。
 *
 * <p>写路径全部要求调用方已经持有目标 Environment 的行锁（{@code for key share}），并按 {@code environment_inventory} →
 * {@code environment_skill_source}（{@code source_id} 升序）的顺序继续取锁；本接口不自行 编排事务，也不做重试。
 */
public interface SkillSourceRepository {

  // ---------------------------------------------------------------------------------------------
  // inventory 头
  // ---------------------------------------------------------------------------------------------

  /** 读取 inventory 头；Environment 没有 inventory 行时返回 null。 */
  EnvironmentInventory getInventory(UUID environmentId);

  /** 以 {@code FOR UPDATE} 读取 inventory 头；Environment 没有 inventory 行时返回 null。 */
  EnvironmentInventory lockInventory(UUID environmentId);

  /** 插入 {@code source_set_version = 0} 的 inventory 行，使该 Environment 拥有持久 inventory 身份。 */
  boolean createInventory(UUID environmentId);

  /**
   * 来源集合成员关系变化时前进期望代际。
   *
   * <p>只有创建/删除来源才推进；来源配置编辑不改变集合成员关系，因此不动本代际。
   */
  boolean incrementSourceSetVersion(UUID environmentId);

  /**
   * READY 报告推进 inventory：期望集合代际必须仍等于 {@code sourceSetVersion}，且已应用代际不得回退。
   *
   * @return 接受并推进返回 true；报告已过期返回 false
   */
  boolean applyReadyReport(
      UUID environmentId,
      long sourceSetVersion,
      int capabilitiesVersion,
      String operatingSystem,
      String timeZone,
      String note,
      String rootPath,
      UUID ownerNodeId,
      UUID leaseToken);

  // ---------------------------------------------------------------------------------------------
  // 来源配置
  // ---------------------------------------------------------------------------------------------

  /** 按 {@code source_id} 升序列出来源配置（稳定序）。 */
  List<SkillSource> listSources(UUID environmentId);

  /** 读取单个来源配置；不存在返回 null。 */
  SkillSource getSource(UUID environmentId, UUID sourceId);

  /** 以 {@code FOR UPDATE} 读取单个来源配置；不存在返回 null。 */
  SkillSource lockSource(UUID environmentId, UUID sourceId);

  /** 以 {@code FOR UPDATE} 按 {@code source_id} 升序锁定全部来源行。 */
  List<SkillSource> lockAllSources(UUID environmentId);

  /** 插入新来源配置行（{@code version 0}、{@code UNAPPLIED}、无 applied 三元组）。 */
  boolean createSource(SkillSource source);

  /** CAS 整体替换来源配置：状态回 {@code UNAPPLIED}、清空 last error、保留既有 applied 三元组并推进 {@code version}。 */
  boolean updateSourceByVersion(SkillSource source, long expectedVersion);

  /** CAS 硬删除来源；该来源的持久 inventory 行由复合 FK 级联删除。 */
  boolean deleteSourceByVersion(UUID environmentId, UUID sourceId, long expectedVersion);

  boolean existsSource(UUID environmentId);

  /**
   * 把来源标记为 READY 并原子写入本次发现的 applied 事实与诊断。
   *
   * <p>围栏参数是 {@code appliedVersion}：只有当行版本仍等于它、且既有 applied 代际不回退时才生效。
   */
  boolean markSourceReady(
      UUID environmentId,
      UUID sourceId,
      long appliedVersion,
      String appliedRevision,
      List<DaemonSkillDiagnostic> diagnostics);

  /**
   * 按版本围栏将来源标记为 FAILED 并写入错误分类码与错误描述；陈旧版本无操作返回 false。
   *
   * <p>既有的 {@code applied_version}/{@code applied_revision}/{@code last_applied_at} 事实及 {@code
   * environment_skill} 行完整保留。
   */
  boolean markSourceFailed(
      UUID environmentId, UUID sourceId, long sourceVersion, String safeCode, String safeMessage);

  // ---------------------------------------------------------------------------------------------
  // 持久 inventory
  // ---------------------------------------------------------------------------------------------

  /** 按 {@code (source_id, name)} 升序列出该 Environment 的全部持久 inventory 行（含陈旧的旧配置行）。 */
  List<SkillInventoryEntry> listSkills(UUID environmentId);

  /**
   * 列出当前可用于新规划的 Skill 行。
   *
   * <p>可用性要求来源 READY、{@code applied_version = version} 且行的 {@code source_version} 等于该 {@code
   * version}；陈旧行仍可展示但不构成规划候选。
   */
  List<SkillInventoryEntry> listUsableSkills(UUID environmentId);

  /** 删除某来源在该 Environment 下的全部持久 inventory 行。 */
  void deleteSourceSkills(UUID environmentId, UUID sourceId);

  /** 批量写入持久 inventory 行。 */
  void insertSkills(List<SkillInventoryEntry> skills);

  /** 原子替换某来源的全部持久 inventory 行（同一事务内先删后插）。 */
  void replaceSourceSkills(UUID environmentId, UUID sourceId, List<SkillInventoryEntry> skills);
}
