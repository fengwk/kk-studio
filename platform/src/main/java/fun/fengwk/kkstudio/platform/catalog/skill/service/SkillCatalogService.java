package fun.fengwk.kkstudio.platform.catalog.skill.service;

import fun.fengwk.kkstudio.share.ai.skill.SkillDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUpdateDTO;

import java.util.List;

/**
 * Platform 全局 Skill 目录的查询与 package 生命周期应用服务。
 *
 * <p>package 更新是一次完整替换：每次都安装一个全新的 {@code (name, version)}，旧版本永久保留并置为非活跃；删除只把当前版本与其 Skill
 * 行置为非活跃。任何会停用仍被 Agent 引用的 Skill 的更新/删除都会被拒绝。
 */
public interface SkillCatalogService {

  /** 全部活跃 Skill 元数据，按 {@code name} 稳定排序。 */
  List<SkillDTO> listSkills();

  /** 全部活跃 package 摘要，按 {@code package_name asc, package_version asc} 排序。 */
  List<SkillPackageDTO> listPackages();

  /** 单个活跃 package 详情（含每个 Skill 的精确正文），供客户端编辑。 */
  SkillPackageDetailDTO getPackage(String packageName);

  /** 创建新的 package 版本并使其成为当前版本。 */
  SkillPackageDetailDTO createPackage(SkillPackageCreateDTO createDTO);

  /**
   * 原子安装完整替换并切换当前版本。
   *
   * <p>{@code expectedPackageVersion} 必须匹配当前活跃版本，{@code newPackageVersion} 必须不同且从未被使用过。
   */
  SkillPackageDetailDTO updatePackage(String packageName, SkillPackageUpdateDTO updateDTO);

  /** 删除 package：原子停用其当前 Skill 行并把 {@code expectedPackageVersion} 置为非活跃。 */
  void deletePackage(String packageName, String expectedPackageVersion);
}
