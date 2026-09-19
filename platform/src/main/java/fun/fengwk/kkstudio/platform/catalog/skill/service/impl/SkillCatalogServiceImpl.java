package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.converter.SkillCatalogConverter;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
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
 * <p>锁顺序统一为「活跃 package 行 → 相关活跃 skill 行（name 升序）」：Agent 创建/更新只取后半段且同样按 name 升序，因此两组写路径
 * 按同一行顺序串行化，不会死锁也不会产生并发悬空引用。package 版本与内容行永不复用、永不物理删除；更新安装新版本并原子切换 active，删除只把当前版本 与其 Skill 行置为非活跃。
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
    return skillCatalogRepository.listActiveSkills().stream().map(converter::convertSkill).toList();
  }

  @Override
  public List<SkillPackageDTO> listPackages() {
    return skillCatalogRepository.listActivePackages().stream()
        .map(skillPackage -> converter.convertPackage(skillPackage, activeSkills(skillPackage)))
        .toList();
  }

  @Override
  public SkillPackageDetailDTO getPackage(String packageName) {
    SkillPackage skillPackage = skillCatalogRepository.getActivePackage(packageName);
    if (skillPackage == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return converter.convertPackageDetail(skillPackage, activeSkills(skillPackage));
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
    requireUnusedVersion(skillPackage);
    try {
      installVersion(mutation);
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
    requireUnusedVersion(replacement);
    // 变更前后都按 name 升序锁定相关活跃 skill 行，再对将移除的名称做引用保护。
    guard.lockActiveSkills(unionNames(current, mutation));
    guard.ensureRemovable(removedNames(current, mutation));
    try {
      installVersion(mutation);
      activate(replacement.getPackageName(), replacement.getPackageVersion(), current);
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "package version or skill name already exists", error);
    }
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
    List<String> currentNames = activeSkillNames(current);
    guard.lockActiveSkills(currentNames);
    guard.ensureRemovable(currentNames);
    skillCatalogRepository.setSkillsActiveByPackage(
        packageName, current.getPackageVersion(), false);
    if (!skillCatalogRepository.setPackageActive(packageName, current.getPackageVersion(), false)) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
  }

  /** 写入新的 package 版本行与其 Skill 内容行；两者都以 {@code active=false} 起步，活跃切换由 {@link #activate} 负责。 */
  private void installVersion(SkillPackageMutationFactory.Mutation mutation) {
    SkillPackage skillPackage = mutation.skillPackage();
    if (!skillCatalogRepository.insertPackage(skillPackage)) {
      throw new IllegalStateException("create skill package version failed");
    }
    skillCatalogRepository.insertSkills(mutation.skills());
  }

  /**
   * 切换活跃版本：每个 package 名至多一个活跃版本、每个 Skill 名至多一个活跃行，因此先停用旧版本行再激活新版本行。
   *
   * <p>创建时旧版本为 null，新版本直接激活。
   */
  private void activate(String packageName, String packageVersion, SkillPackage previous) {
    if (previous != null) {
      skillCatalogRepository.setSkillsActiveByPackage(
          packageName, previous.getPackageVersion(), false);
      if (!skillCatalogRepository.setPackageActive(
          packageName, previous.getPackageVersion(), false)) {
        throw new IllegalStateException("deactivate previous skill package version failed");
      }
    }
    skillCatalogRepository.setSkillsActiveByPackage(packageName, packageVersion, true);
    if (!skillCatalogRepository.setPackageActive(packageName, packageVersion, true)) {
      throw new IllegalStateException("activate skill package version failed");
    }
  }

  /** 请求版本必须从未被使用过（含非活跃的历史版本）。 */
  private void requireUnusedVersion(SkillPackage skillPackage) {
    if (skillCatalogRepository.existsPackage(
        skillPackage.getPackageName(), skillPackage.getPackageVersion())) {
      throw new AiDuplicateException(
          RESOURCE,
          "package version has already been used: "
              + skillPackage.getPackageName()
              + "/"
              + skillPackage.getPackageVersion());
    }
  }

  private List<Skill> activeSkills(SkillPackage skillPackage) {
    return skillCatalogRepository.listActiveSkillsByPackage(
        skillPackage.getPackageName(), skillPackage.getPackageVersion());
  }

  private List<String> activeSkillNames(SkillPackage skillPackage) {
    return activeSkills(skillPackage).stream().map(Skill::getName).toList();
  }

  /** 变更前需锁定的全部相关 Skill 名：当前 package 已有名 ∪ 替换后的名。 */
  private List<String> unionNames(
      SkillPackage current, SkillPackageMutationFactory.Mutation mutation) {
    Set<String> names = new LinkedHashSet<>(activeSkillNames(current));
    mutation.skills().forEach(skill -> names.add(skill.getName()));
    return names.stream().sorted().toList();
  }

  /** 变更后将从活跃目录消失、因而需要引用保护的 Skill 名。 */
  private List<String> removedNames(
      SkillPackage current, SkillPackageMutationFactory.Mutation mutation) {
    Set<String> replacementNames = new LinkedHashSet<>();
    mutation.skills().forEach(skill -> replacementNames.add(skill.getName()));
    return activeSkillNames(current).stream()
        .filter(name -> !replacementNames.contains(name))
        .toList();
  }
}
