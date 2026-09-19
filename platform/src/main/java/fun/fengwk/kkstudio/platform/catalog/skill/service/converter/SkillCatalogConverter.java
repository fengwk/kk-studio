package fun.fengwk.kkstudio.platform.catalog.skill.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.share.ai.skill.SkillDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO;

import java.util.List;

/** 把 Skill 目录领域模型转换为公开 DTO。 */
@Component
public class SkillCatalogConverter {

  /** 当前 Skill 元数据（不含正文）。 */
  public SkillDTO convertSkill(Skill skill) {
    if (skill == null) {
      return null;
    }
    SkillDTO dto = new SkillDTO();
    dto.setName(skill.getName());
    dto.setDescription(skill.getDescription());
    dto.setPackageName(skill.getPackageName());
    dto.setPackageVersion(skill.getPackageVersion());
    return dto;
  }

  /** package 摘要：只暴露一个 package 版本的身份与其 Skill 元数据。 */
  public SkillPackageDTO convertPackage(SkillPackage skillPackage, List<Skill> skills) {
    if (skillPackage == null) {
      return null;
    }
    SkillPackageDTO dto = new SkillPackageDTO();
    dto.setName(skillPackage.getPackageName());
    dto.setPackageVersion(skillPackage.getPackageVersion());
    dto.setDescription(skillPackage.getDescription());
    dto.setSkills(skills.stream().map(this::convertSkill).toList());
    return dto;
  }

  /** package 详情：额外携带每个 Skill 的精确正文，可直接用于编辑。 */
  public SkillPackageDetailDTO convertPackageDetail(SkillPackage skillPackage, List<Skill> skills) {
    if (skillPackage == null) {
      return null;
    }
    SkillPackageDetailDTO dto = new SkillPackageDetailDTO();
    dto.setName(skillPackage.getPackageName());
    dto.setPackageVersion(skillPackage.getPackageVersion());
    dto.setDescription(skillPackage.getDescription());
    dto.setSkills(skills.stream().map(this::convertSkillDefinition).toList());
    return dto;
  }

  private SkillDefinitionDTO convertSkillDefinition(Skill skill) {
    SkillDefinitionDTO dto = new SkillDefinitionDTO();
    dto.setName(skill.getName());
    dto.setDescription(skill.getDescription());
    dto.setContent(skill.getContent());
    return dto;
  }
}
