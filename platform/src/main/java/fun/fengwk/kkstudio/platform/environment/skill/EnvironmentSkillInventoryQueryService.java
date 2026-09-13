package fun.fengwk.kkstudio.platform.environment.skill;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInventoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillDTO;

import java.util.List;
import java.util.Objects;

/**
 * 持久 Skill inventory 的只读查询。
 *
 * <p>查询不依赖任何连接状态：Environment 离线时仍返回最近一次被接受的报告与可用于新规划的 Skill，因此规划不必等待 daemon 上线。 可用性由 {@link
 * SkillSourceRepository#listUsableSkills} 的持久事实定义（来源 READY、{@code applied_version = version}、
 * 行版本等于当前 {@code version}），陈旧行不进入结果。
 */
@AllArgsConstructor
@Service
public class EnvironmentSkillInventoryQueryService {

  private final EnvironmentRepository environmentRepository;
  private final SkillSourceRepository skillSourceRepository;

  /** 读取 inventory 头；Environment 或 inventory 行不存在时 404。 */
  public EnvironmentInventoryDTO getInventory(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    requireEnvironment(environmentId);
    EnvironmentInventory inventory = skillSourceRepository.getInventory(environmentId.value());
    if (inventory == null) {
      throw new AiResourceNotFoundException("environment_inventory");
    }
    return toDto(inventory);
  }

  /** 列出当前可用于新规划的持久 Skill；Environment 不存在时 404，无可用 Skill 时返回空列表。 */
  public List<EnvironmentSkillDTO> listUsableSkills(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    requireEnvironment(environmentId);
    return skillSourceRepository.listUsableSkills(environmentId.value()).stream()
        .map(EnvironmentSkillInventoryQueryService::toDto)
        .toList();
  }

  /** 列出该 Environment 的全部持久 Skill 行（含陈旧行），供来源详情展示。 */
  public List<EnvironmentSkillDTO> listSkills(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    requireEnvironment(environmentId);
    return skillSourceRepository.listSkills(environmentId.value()).stream()
        .map(EnvironmentSkillInventoryQueryService::toDto)
        .toList();
  }

  /**
   * 读取最近一次被接受报告的宿主 root 展示路径。
   *
   * <p>这是与连接状态解耦的持久事实：Environment 离线时仍可展示上一次宿主 root；从未接受过报告时返回 null。仅供卡片展示，不参与任何 路径解析，也不构成工具
   * workdir 默认值。
   */
  public String findReportedRootPath(EnvironmentId environmentId) {
    Objects.requireNonNull(environmentId, "environmentId");
    EnvironmentInventory inventory = skillSourceRepository.getInventory(environmentId.value());
    return inventory == null ? null : inventory.getRootPath();
  }

  private void requireEnvironment(EnvironmentId environmentId) {
    if (environmentRepository.getById(environmentId.value()) == null) {
      throw new AiResourceNotFoundException("environment");
    }
  }

  private static EnvironmentInventoryDTO toDto(EnvironmentInventory inventory) {
    EnvironmentInventoryDTO dto = new EnvironmentInventoryDTO();
    dto.setEnvironmentId(inventory.getEnvironmentId().toString());
    dto.setSourceSetVersion(CatalogVersions.format(inventory.getSourceSetVersion()));
    dto.setAppliedSourceSetVersion(
        inventory.getAppliedSourceSetVersion() == null
            ? null
            : CatalogVersions.format(inventory.getAppliedSourceSetVersion()));
    dto.setCapabilitiesVersion(inventory.getCapabilitiesVersion());
    dto.setOperatingSystem(inventory.getOperatingSystem());
    dto.setTimeZone(inventory.getTimeZone());
    dto.setNote(inventory.getNote());
    dto.setRootPath(inventory.getRootPath());
    dto.setReportedAt(inventory.getReportedAt());
    dto.setCreateTime(inventory.getCreateTime());
    dto.setUpdateTime(inventory.getUpdateTime());
    return dto;
  }

  private static EnvironmentSkillDTO toDto(SkillInventoryEntry entry) {
    EnvironmentSkillDTO dto = new EnvironmentSkillDTO();
    dto.setSourceId(entry.getSourceId().toString());
    dto.setName(entry.getName());
    dto.setSourceVersion(CatalogVersions.format(entry.getSourceVersion()));
    dto.setDescription(entry.getDescription());
    dto.setBaseDirectory(entry.getBaseDirectory());
    dto.setContentRevision(entry.getContentRevision());
    dto.setDiscoveredAt(entry.getDiscoveredAt());
    return dto;
  }
}
