package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.mapper.SkillCatalogMapper;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillDO;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.Collection;
import java.util.List;

/** 基于 PostgreSQL 的 Platform 全局 Skill 目录仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlSkillCatalogRepository implements SkillCatalogRepository {

  private final SkillCatalogMapper skillCatalogMapper;

  @Override
  public List<Skill> listActiveSkills() {
    return skillCatalogMapper.listActiveSkills().stream().map(this::toSkill).toList();
  }

  @Override
  public List<Skill> listActiveSkillsByPackage(String packageName, String packageVersion) {
    return skillCatalogMapper.listActiveSkillsByPackage(packageName, packageVersion).stream()
        .map(this::toSkill)
        .toList();
  }

  @Override
  public Skill getActiveSkill(String name) {
    return toSkill(skillCatalogMapper.getActiveSkill(name));
  }

  @Override
  public Skill getSkill(String packageName, String packageVersion, String name) {
    return toSkill(skillCatalogMapper.getSkill(packageName, packageVersion, name));
  }

  @Override
  public List<Skill> lockActiveSkillsByNames(Collection<String> names) {
    if (names.isEmpty()) {
      return List.of();
    }
    return skillCatalogMapper.lockActiveSkillsByNames(names).stream().map(this::toSkill).toList();
  }

  @Override
  public List<SkillPackage> listActivePackages() {
    return skillCatalogMapper.listActivePackages().stream().map(this::toPackage).toList();
  }

  @Override
  public SkillPackage getActivePackage(String packageName) {
    return toPackage(skillCatalogMapper.getActivePackage(packageName));
  }

  @Override
  public SkillPackage lockActivePackage(String packageName) {
    return toPackage(skillCatalogMapper.lockActivePackage(packageName));
  }

  @Override
  public SkillPackage getPackage(String packageName, String packageVersion) {
    return toPackage(skillCatalogMapper.getPackage(packageName, packageVersion));
  }

  @Override
  public boolean existsPackage(String packageName, String packageVersion) {
    return skillCatalogMapper.existsPackage(packageName, packageVersion);
  }

  @Override
  public boolean insertPackage(SkillPackage skillPackage) {
    return skillCatalogMapper.insertPackage(toPackageDO(skillPackage)) == 1;
  }

  @Override
  public void insertSkills(List<Skill> skills) {
    if (!skills.isEmpty()) {
      skillCatalogMapper.insertSkills(skills.stream().map(this::toSkillDO).toList());
    }
  }

  @Override
  public boolean setPackageActive(String packageName, String packageVersion, boolean active) {
    return skillCatalogMapper.setPackageActive(packageName, packageVersion, active) == 1;
  }

  @Override
  public void setSkillsActiveByPackage(String packageName, String packageVersion, boolean active) {
    skillCatalogMapper.setSkillsActiveByPackage(packageName, packageVersion, active);
  }

  private SkillPackage toPackage(SkillPackageDO row) {
    if (row == null) {
      return null;
    }
    SkillPackage target = new SkillPackage();
    target.setPackageName(row.getPackageName());
    target.setPackageVersion(row.getPackageVersion());
    target.setDescription(row.getDescription());
    target.setActive(Boolean.TRUE.equals(row.getActive()));
    target.setCreateTime(row.getCreateTime());
    return target;
  }

  private SkillPackageDO toPackageDO(SkillPackage model) {
    SkillPackageDO target = new SkillPackageDO();
    target.setPackageName(model.getPackageName());
    target.setPackageVersion(model.getPackageVersion());
    target.setDescription(model.getDescription());
    target.setActive(model.isActive());
    return target;
  }

  private Skill toSkill(SkillDO row) {
    if (row == null) {
      return null;
    }
    Skill target = new Skill();
    target.setPackageName(row.getPackageName());
    target.setPackageVersion(row.getPackageVersion());
    target.setName(row.getName());
    target.setDescription(row.getDescription());
    target.setContent(row.getContent());
    target.setActive(Boolean.TRUE.equals(row.getActive()));
    target.setCreateTime(row.getCreateTime());
    return target;
  }

  private SkillDO toSkillDO(Skill model) {
    SkillDO target = new SkillDO();
    target.setPackageName(model.getPackageName());
    target.setPackageVersion(model.getPackageVersion());
    target.setName(model.getName());
    target.setDescription(model.getDescription());
    target.setContent(model.getContent());
    target.setActive(model.isActive());
    return target;
  }
}
