package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.mapper.SkillCatalogMapper;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.CurrentSkillDO;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillRevisionDO;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.CurrentSkill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillRevision;

import java.util.Collection;
import java.util.List;

/** 基于 PostgreSQL 的 Platform 全局 Skill 目录仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlSkillCatalogRepository implements SkillCatalogRepository {

  private final SkillCatalogMapper skillCatalogMapper;

  @Override
  public List<CurrentSkill> listCurrentSkills() {
    return skillCatalogMapper.listCurrentSkills().stream().map(this::toCurrentSkill).toList();
  }

  @Override
  public List<CurrentSkill> listCurrentSkillsByPackage(String packageName, String packageVersion) {
    return skillCatalogMapper.listCurrentSkillsByPackage(packageName, packageVersion).stream()
        .map(this::toCurrentSkill)
        .toList();
  }

  @Override
  public CurrentSkill getCurrentSkill(String name) {
    return toCurrentSkill(skillCatalogMapper.getCurrentSkill(name));
  }

  @Override
  public List<CurrentSkill> lockCurrentSkillsByNames(Collection<String> names) {
    if (names.isEmpty()) {
      return List.of();
    }
    return skillCatalogMapper.lockCurrentSkillsByNames(names).stream()
        .map(this::toCurrentSkill)
        .toList();
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
  public SkillRevision getRevision(String packageName, String packageVersion, String name) {
    return toRevision(skillCatalogMapper.getRevision(packageName, packageVersion, name));
  }

  @Override
  public List<SkillRevision> listRevisions(String packageName, String packageVersion) {
    return skillCatalogMapper.listRevisions(packageName, packageVersion).stream()
        .map(this::toRevision)
        .toList();
  }

  @Override
  public boolean insertPackage(SkillPackage skillPackage) {
    return skillCatalogMapper.insertPackage(toPackageDO(skillPackage)) == 1;
  }

  @Override
  public void insertRevisions(List<SkillRevision> revisions) {
    if (!revisions.isEmpty()) {
      skillCatalogMapper.insertRevisions(revisions.stream().map(this::toRevisionDO).toList());
    }
  }

  @Override
  public boolean setPackageActive(String packageName, String packageVersion, boolean active) {
    return skillCatalogMapper.setPackageActive(packageName, packageVersion, active) == 1;
  }

  @Override
  public void deleteCurrentSkillsByPackage(String packageName) {
    skillCatalogMapper.deleteCurrentSkillsByPackage(packageName);
  }

  @Override
  public void insertCurrentSkills(List<CurrentSkill> skills) {
    if (!skills.isEmpty()) {
      skillCatalogMapper.insertCurrentSkills(skills.stream().map(this::toCurrentSkillDO).toList());
    }
  }

  private SkillPackage toPackage(SkillPackageDO row) {
    if (row == null) {
      return null;
    }
    SkillPackage target = new SkillPackage();
    target.setPackageName(row.getPackageName());
    target.setPackageVersion(row.getPackageVersion());
    target.setDescription(row.getDescription());
    target.setPackageRevision(row.getPackageRevision());
    target.setActive(Boolean.TRUE.equals(row.getActive()));
    target.setCreateTime(row.getCreateTime());
    return target;
  }

  private SkillPackageDO toPackageDO(SkillPackage model) {
    SkillPackageDO target = new SkillPackageDO();
    target.setPackageName(model.getPackageName());
    target.setPackageVersion(model.getPackageVersion());
    target.setDescription(model.getDescription());
    target.setPackageRevision(model.getPackageRevision());
    target.setActive(model.isActive());
    return target;
  }

  private SkillRevision toRevision(SkillRevisionDO row) {
    if (row == null) {
      return null;
    }
    SkillRevision target = new SkillRevision();
    target.setPackageName(row.getPackageName());
    target.setPackageVersion(row.getPackageVersion());
    target.setName(row.getName());
    target.setDescription(row.getDescription());
    target.setContent(row.getContent());
    target.setContentRevision(row.getContentRevision());
    target.setCreateTime(row.getCreateTime());
    return target;
  }

  private SkillRevisionDO toRevisionDO(SkillRevision model) {
    SkillRevisionDO target = new SkillRevisionDO();
    target.setPackageName(model.getPackageName());
    target.setPackageVersion(model.getPackageVersion());
    target.setName(model.getName());
    target.setDescription(model.getDescription());
    target.setContent(model.getContent());
    target.setContentRevision(model.getContentRevision());
    return target;
  }

  private CurrentSkill toCurrentSkill(CurrentSkillDO row) {
    if (row == null) {
      return null;
    }
    CurrentSkill target = new CurrentSkill();
    target.setName(row.getName());
    target.setPackageName(row.getPackageName());
    target.setPackageVersion(row.getPackageVersion());
    target.setDescription(row.getDescription());
    target.setContentRevision(row.getContentRevision());
    target.setContent(row.getContent());
    return target;
  }

  private CurrentSkillDO toCurrentSkillDO(CurrentSkill model) {
    CurrentSkillDO target = new CurrentSkillDO();
    target.setName(model.getName());
    target.setPackageName(model.getPackageName());
    target.setPackageVersion(model.getPackageVersion());
    target.setDescription(model.getDescription());
    target.setContentRevision(model.getContentRevision());
    target.setContent(model.getContent());
    return target;
  }
}
