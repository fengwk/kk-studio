package fun.fengwk.kkstudio.platform.catalog.skill.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.skill.SkillManifestEntryDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;

import java.util.List;

/** 把 Skill Package 权威事实转换为对外投影。 */
@Component
public class SkillCatalogConverter {

  /** Card 状态：从未检查。 */
  public static final String CHECK_STATUS_UNCHECKED = "UNCHECKED";

  /** Card 状态：观察值等于当前值。 */
  public static final String CHECK_STATUS_UP_TO_DATE = "UP_TO_DATE";

  /** Card 状态：观察值不同于当前值。 */
  public static final String CHECK_STATUS_UPDATE_AVAILABLE = "UPDATE_AVAILABLE";

  /** Card 状态：最近检查失败。 */
  public static final String CHECK_STATUS_CHECK_FAILED = "CHECK_FAILED";

  public SkillPackageDTO convert(SkillPackage skillPackage) {
    if (skillPackage == null) {
      return null;
    }
    SkillPackageDTO dto = new SkillPackageDTO();
    dto.setPackageName(skillPackage.getPackageName());
    dto.setDescription(skillPackage.getDescription());
    dto.setRepositoryUrl(skillPackage.getRepositoryUrl());
    dto.setBranch(skillPackage.getBranch());
    dto.setCurrentCommit(skillPackage.getCurrentCommit());
    dto.setObservedHeadCommit(skillPackage.getObservedHeadCommit());
    dto.setHeadCheckedAt(skillPackage.getHeadCheckedAt());
    dto.setHeadCheckError(skillPackage.getHeadCheckError());
    dto.setCheckStatus(checkStatus(skillPackage));
    dto.setSkills(convertSkills(skillPackage.getSkills()));
    dto.setVersion(CatalogVersions.format(skillPackage.getVersion()));
    dto.setCreateTime(skillPackage.getCreateTime());
    dto.setUpdateTime(skillPackage.getUpdateTime());
    return dto;
  }

  private List<SkillManifestEntryDTO> convertSkills(List<SkillManifestEntry> skills) {
    if (skills == null) {
      return List.of();
    }
    return skills.stream().map(this::convertSkill).toList();
  }

  private SkillManifestEntryDTO convertSkill(SkillManifestEntry entry) {
    SkillManifestEntryDTO dto = new SkillManifestEntryDTO();
    dto.setName(entry.name());
    dto.setDescription(entry.description());
    return dto;
  }

  /** Card 状态只由这组事实派生：检查错误优先，其次比较观察值与当前值。 */
  private String checkStatus(SkillPackage skillPackage) {
    if (skillPackage.getHeadCheckError() != null) {
      return CHECK_STATUS_CHECK_FAILED;
    }
    if (skillPackage.getObservedHeadCommit() == null) {
      return CHECK_STATUS_UNCHECKED;
    }
    return skillPackage.getObservedHeadCommit().equals(skillPackage.getCurrentCommit())
        ? CHECK_STATUS_UP_TO_DATE
        : CHECK_STATUS_UPDATE_AVAILABLE;
  }
}
