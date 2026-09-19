package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.converter.SkillCatalogConverter;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.CurrentSkill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillRevision;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.skill.SkillDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUpdateDTO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Platform 全局 Skill 目录 CRUD。
 *
 * <p>锁顺序统一为「活跃 package 行 → 相关当前 skill 行（name 升序）」：Agent 创建/更新只取后半段且同样按 name 升序，因此两组写路径
 * 按同一行顺序串行化，不会死锁也不会产生并发悬空引用。package 版本与 revision 行永不复用、永不物理删除；更新与删除都先原子替换当前 Skill 目录行，再切换/清空活跃版本。
 */
@AllArgsConstructor
@Service
public class SkillCatalogServiceImpl implements SkillCatalogService {

  private static final String RESOURCE = SkillPackageMutationFactory.RESOURCE;

  private final SkillCatalogRepository skillCatalogRepository;
  private final SkillPackageMutationFactory mutationFactory;
  private final SkillCatalogConverter converter;
  private final SkillPackageGuard guard;

  @Override
  public List<SkillDTO> listSkills() {
    return skillCatalogRepository.listCurrentSkills().stream()
        .map(converter::convertSkill)
        .toList();
  }

  @Override
  public List<SkillPackageDTO> listPackages() {
    return skillCatalogRepository.listActivePackages().stream()
        .map(skillPackage -> converter.convertPackage(skillPackage, currentSkills(skillPackage)))
        .toList();
  }

  @Override
  public SkillPackageDetailDTO getPackage(String packageName) {
    SkillPackage skillPackage = skillCatalogRepository.getActivePackage(packageName);
    if (skillPackage == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return converter.convertPackageDetail(skillPackage, currentSkills(skillPackage));
  }

  @Override
  @Transactional
  public SkillPackageDetailDTO createPackage(SkillPackageCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    SkillPackageMutationFactory.Mutation mutation =
        mutationFactory.newMutation(
            createDTO.getName(),
            createDTO.getPackageVersion(),
            createDTO.getDescription(),
            createDTO.getSkills());
    SkillPackage skillPackage = mutation.skillPackage();
    if (skillCatalogRepository.getActivePackage(skillPackage.getPackageName()) != null) {
      throw new AiDuplicateException(
          RESOURCE, "package already has an active version: " + skillPackage.getPackageName());
    }
    if (skillCatalogRepository.existsPackage(
        skillPackage.getPackageName(), skillPackage.getPackageVersion())) {
      throw new AiDuplicateException(
          RESOURCE,
          "package version has already been used: "
              + skillPackage.getPackageName()
              + "/"
              + skillPackage.getPackageVersion());
    }
    try {
      insertImmutableVersion(mutation);
      applyCurrentSkills(skillPackage, mutation.revisions());
      activate(skillPackage.getPackageName(), skillPackage.getPackageVersion(), null);
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "package version or skill name already exists", error);
    }
    return getPackage(skillPackage.getPackageName());
  }

  @Override
  @Transactional
  public SkillPackageDetailDTO updatePackage(String packageName, SkillPackageUpdateDTO updateDTO) {
    if (updateDTO == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    SkillPackage current = guard.requireActivePackageForUpdate(packageName);
    String expected = updateDTO.getExpectedPackageVersion();
    if (expected == null || !expected.equals(current.getPackageVersion())) {
      throw new AiVersionConflictException(
          RESOURCE, String.valueOf(expected), current.getPackageVersion());
    }
    String newVersion = updateDTO.getNewPackageVersion();
    if (newVersion != null && newVersion.equals(current.getPackageVersion())) {
      throw new AiValidationException(
          RESOURCE, "newPackageVersion must differ from the current package version");
    }
    SkillPackageMutationFactory.Mutation mutation =
        mutationFactory.newMutation(
            packageName, newVersion, updateDTO.getDescription(), updateDTO.getSkills());
    SkillPackage replacement = mutation.skillPackage();
    if (skillCatalogRepository.existsPackage(
        replacement.getPackageName(), replacement.getPackageVersion())) {
      throw new AiDuplicateException(
          RESOURCE,
          "package version has already been used: "
              + replacement.getPackageName()
              + "/"
              + replacement.getPackageVersion());
    }
    // 变更前后都按 name 升序锁定相关当前 skill 行，再对将移除的名称做引用保护。
    guard.lockCurrentSkills(unionNames(current, mutation));
    guard.ensureRemovable(removedNames(current, mutation));
    insertImmutableVersion(mutation);
    applyCurrentSkills(replacement, mutation.revisions());
    activate(
        replacement.getPackageName(), replacement.getPackageVersion(), current.getPackageVersion());
    return getPackage(packageName);
  }

  @Override
  @Transactional
  public void deletePackage(String packageName, String expectedPackageVersion) {
    SkillPackage current = guard.requireActivePackageForUpdate(packageName);
    if (expectedPackageVersion == null
        || !expectedPackageVersion.equals(current.getPackageVersion())) {
      throw new AiVersionConflictException(
          RESOURCE, String.valueOf(expectedPackageVersion), current.getPackageVersion());
    }
    List<String> currentNames = currentSkillNames(current);
    guard.lockCurrentSkills(currentNames);
    guard.ensureRemovable(currentNames);
    skillCatalogRepository.deleteCurrentSkillsByPackage(packageName);
    if (!skillCatalogRepository.setPackageActive(packageName, current.getPackageVersion(), false)) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
  }

  /** 写入不可变的 package 版本行与全部 revision 行；活跃切换由 {@link #activate} 负责。 */
  private void insertImmutableVersion(SkillPackageMutationFactory.Mutation mutation) {
    SkillPackage skillPackage = mutation.skillPackage();
    if (!skillCatalogRepository.insertPackage(skillPackage)) {
      throw new IllegalStateException("create skill package version failed");
    }
    skillCatalogRepository.insertRevisions(mutation.revisions());
  }

  /** 原子替换该 package 的当前 Skill 目录行；跨 package 名称冲突在库层收敛为明确的重复错误。 */
  private void applyCurrentSkills(SkillPackage skillPackage, List<SkillRevision> revisions) {
    skillCatalogRepository.deleteCurrentSkillsByPackage(skillPackage.getPackageName());
    List<CurrentSkill> skills =
        revisions.stream()
            .map(
                revision -> {
                  CurrentSkill skill = new CurrentSkill();
                  skill.setName(revision.getName());
                  skill.setPackageName(skillPackage.getPackageName());
                  skill.setPackageVersion(skillPackage.getPackageVersion());
                  skill.setDescription(revision.getDescription());
                  skill.setContentRevision(revision.getContentRevision());
                  return skill;
                })
            .toList();
    try {
      skillCatalogRepository.insertCurrentSkills(skills);
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "skill name is already owned by another active package", error);
    }
  }

  /** 切换活跃版本：每个 package 名至多一个活跃版本，因此先放开旧版本再激活新版本；创建时旧版本为 null。 */
  private void activate(String packageName, String packageVersion, String previousVersion) {
    if (previousVersion != null
        && !skillCatalogRepository.setPackageActive(packageName, previousVersion, false)) {
      throw new IllegalStateException("deactivate previous skill package version failed");
    }
    if (!skillCatalogRepository.setPackageActive(packageName, packageVersion, true)) {
      throw new IllegalStateException("activate skill package version failed");
    }
  }

  private List<CurrentSkill> currentSkills(SkillPackage skillPackage) {
    return skillCatalogRepository.listCurrentSkillsByPackage(
        skillPackage.getPackageName(), skillPackage.getPackageVersion());
  }

  private List<String> currentSkillNames(SkillPackage skillPackage) {
    return currentSkills(skillPackage).stream().map(CurrentSkill::getName).toList();
  }

  /** 变更前需锁定的全部相关 Skill 名：当前 package 已有名 ∪ 替换后的名。 */
  private List<String> unionNames(
      SkillPackage current, SkillPackageMutationFactory.Mutation mutation) {
    Set<String> names = new LinkedHashSet<>(currentSkillNames(current));
    mutation.revisions().forEach(revision -> names.add(revision.getName()));
    return names.stream().sorted().toList();
  }

  /** 变更后将从当前目录消失、因而需要引用保护的 Skill 名。 */
  private List<String> removedNames(
      SkillPackage current, SkillPackageMutationFactory.Mutation mutation) {
    Set<String> replacementNames = new LinkedHashSet<>();
    mutation.revisions().forEach(revision -> replacementNames.add(revision.getName()));
    return currentSkillNames(current).stream()
        .filter(name -> !replacementNames.contains(name))
        .toList();
  }
}
