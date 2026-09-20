package fun.fengwk.kkstudio.platform.catalog.skill.service;

import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePublishDTO;

import java.util.List;

/**
 * Platform 全局 Git Skill Package 的应用服务。
 *
 * <p>package 以不可变 {@code packageName} 键控一个不可变 repository URL，发布内容由人工确认的 exact commit 决定：Create
 * 发布当时的 branch HEAD，Check 只观察 branch HEAD，Update 只发布 Card 已展示的 exact commit。任何写操作都只在事实变化时推进同一个
 * Package {@code version}，陈旧请求统一返回 version conflict。
 */
public interface SkillCatalogService {

  /** 全部 Package，按 {@code packageName} 升序。 */
  List<SkillPackageDTO> listPackages();

  /** 单个 Package；不存在时 404。 */
  SkillPackageDTO getPackage(String packageName);

  /** 创建 Package 并发布当时解析出的 branch HEAD。 */
  SkillPackageDTO createPackage(SkillPackageCreateDTO createDTO);

  /** 编辑可编辑字段（description / branch），不改变已发布内容。 */
  SkillPackageDTO editPackage(String packageName, SkillPackageEditDTO editDTO);

  /** 删除 Package；仍被 Agent {@code SkillRef} 引用时拒绝。 */
  void deletePackage(String packageName, String expectedVersion);

  /** 只观察 branch HEAD，更新观察三元组；失败时保留 current commit 与 manifest。 */
  SkillPackageDTO checkPackage(String packageName, SkillPackageCheckDTO checkDTO);

  /** 发布等于当前观察值的 exact commit，并原子替换 current commit 与 manifest。 */
  SkillPackageDTO updatePackage(String packageName, SkillPackagePublishDTO publishDTO);
}
