package fun.fengwk.kkstudio.platform.catalog.skill.repo;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.Collection;
import java.util.List;

/**
 * Platform 全局 Skill 目录仓库。
 *
 * <p>{@code skill_package} / {@code skill} 行一经写入永不修改、永不物理删除；唯一可变的事实是 {@code active}
 * 标记。写路径要求调用方已开启事务 并按「活跃 package 行 → 活跃 skill 行」的顺序取锁，本接口不自行编排事务。
 */
public interface SkillCatalogRepository {

  // ---------------------------------------------------------------------------------------------
  // 活跃目录
  // ---------------------------------------------------------------------------------------------

  /** 按 {@code name} 升序列出全部活跃 Skill（含正文）。 */
  List<Skill> listActiveSkills();

  /** 按 {@code name} 升序列出某 package 版本的活跃 Skill。 */
  List<Skill> listActiveSkillsByPackage(String packageName, String packageVersion);

  /** 读取单个活跃 Skill；不存在返回 null。 */
  Skill getActiveSkill(String name);

  /** 读取精确的 {@code (packageName, packageVersion, name)} 行（含非活跃的历史版本）；不存在返回 null。 */
  Skill getSkill(String packageName, String packageVersion, String name);

  /** 按 {@code name} 升序 {@code FOR UPDATE} 锁定给定名称中存在的活跃 Skill 行。 */
  List<Skill> lockActiveSkillsByNames(Collection<String> names);

  // ---------------------------------------------------------------------------------------------
  // package 版本
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
  // 写路径
  // ---------------------------------------------------------------------------------------------

  /** 安装新的 package 版本行（{@code active=false} 起步，由调用方随后显式激活）。 */
  boolean insertPackage(SkillPackage skillPackage);

  /** 批量写入新的 Skill 内容行（{@code active=false} 起步，由调用方随后显式激活）。 */
  void insertSkills(List<Skill> skills);

  /** 把精确的 {@code (packageName, packageVersion)} 置为给定的 active 状态。 */
  boolean setPackageActive(String packageName, String packageVersion, boolean active);

  /** 把某 package 版本的全部 Skill 行置为给定的 active 状态。 */
  void setSkillsActiveByPackage(String packageName, String packageVersion, boolean active);
}
