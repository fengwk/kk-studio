package fun.fengwk.kkstudio.platform.catalog.skill.repo;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.CurrentSkill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillRevision;

import java.util.Collection;
import java.util.List;

/**
 * Platform 全局 Skill 目录仓库。
 *
 * <p>{@code skill_package} / {@code skill_revision} 行一经写入永不修改、永不删除；可变事实只有 {@code active} 标记与 {@code
 * skill} 当前目录行。写路径要求调用方已开启事务并按「活跃 package 行 → 当前 skill 行」的顺序取锁，本接口不自行编排事务。
 */
public interface SkillCatalogRepository {

  // ---------------------------------------------------------------------------------------------
  // 当前目录（skill + skill_revision）
  // ---------------------------------------------------------------------------------------------

  /** 按 {@code name} 升序列出全部当前 Skill（含正文与 revision）。 */
  List<CurrentSkill> listCurrentSkills();

  /** 按 {@code name} 升序列出某 package 版本的当前 Skill。 */
  List<CurrentSkill> listCurrentSkillsByPackage(String packageName, String packageVersion);

  /** 读取单个当前 Skill；不存在返回 null。 */
  CurrentSkill getCurrentSkill(String name);

  /** 按 {@code name} 升序 {@code FOR UPDATE} 锁定给定名称中存在的当前 Skill 行。 */
  List<CurrentSkill> lockCurrentSkillsByNames(Collection<String> names);

  // ---------------------------------------------------------------------------------------------
  // package 版本（不可变）
  // ---------------------------------------------------------------------------------------------

  /** 按 {@code package_name asc, package_version asc} 列出全部活跃 package。 */
  List<SkillPackage> listActivePackages();

  /** 读取某 package 名当前的活跃版本；不存在返回 null。 */
  SkillPackage getActivePackage(String packageName);

  /** 以 {@code FOR UPDATE} 读取某 package 名当前的活跃版本；不存在返回 null。 */
  SkillPackage lockActivePackage(String packageName);

  /** 读取精确的 {@code (packageName, packageVersion)} 版本；不存在返回 null。 */
  SkillPackage getPackage(String packageName, String packageVersion);

  /** 精确版本是否已存在（含非活跃的历史版本）。 */
  boolean existsPackage(String packageName, String packageVersion);

  // ---------------------------------------------------------------------------------------------
  // 不可变 revision
  // ---------------------------------------------------------------------------------------------

  /** 读取精确的 {@code (packageName, packageVersion, name)} revision；不存在返回 null。 */
  SkillRevision getRevision(String packageName, String packageVersion, String name);

  /** 按 {@code name} 升序列出某 package 版本的全部 revision。 */
  List<SkillRevision> listRevisions(String packageName, String packageVersion);

  // ---------------------------------------------------------------------------------------------
  // 写路径
  // ---------------------------------------------------------------------------------------------

  /** 安装新的 package 版本行（{@code active=false} 起步，由调用方随后显式激活）。 */
  boolean insertPackage(SkillPackage skillPackage);

  /** 批量写入不可变 revision 行。 */
  void insertRevisions(List<SkillRevision> revisions);

  /** 把精确的 {@code (packageName, packageVersion)} 置为给定的 active 状态。 */
  boolean setPackageActive(String packageName, String packageVersion, boolean active);

  /** 删除某 package 名全部当前 skill 目录行。 */
  void deleteCurrentSkillsByPackage(String packageName);

  /** 批量写入当前 skill 目录行。 */
  void insertCurrentSkills(List<CurrentSkill> skills);
}
