package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.mapper.SkillPackageMapper;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.impl.model.SkillPackageDO;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;

import java.util.List;
import java.util.Objects;

/** 基于 PostgreSQL 的 Platform 全局 Skill Package 权威行仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlSkillPackageRepository implements SkillPackageRepository {

  private final SkillPackageMapper skillPackageMapper;

  @Override
  public List<SkillPackage> listPackages() {
    return skillPackageMapper.listPackages().stream().map(this::toPackage).toList();
  }

  @Override
  public SkillPackage getPackage(String packageName) {
    return toPackage(skillPackageMapper.getPackage(packageName));
  }

  @Override
  public SkillPackage lockPackage(String packageName) {
    return toPackage(skillPackageMapper.lockPackage(packageName));
  }

  @Override
  public boolean insertPackage(SkillPackage skillPackage) {
    return skillPackageMapper.insertPackage(toPackageDO(skillPackage)) == 1;
  }

  @Override
  public boolean updatePackage(SkillPackage skillPackage, long expectedVersion) {
    return skillPackageMapper.updatePackage(toPackageDO(skillPackage), expectedVersion) == 1;
  }

  @Override
  public boolean deletePackage(String packageName, long expectedVersion) {
    return skillPackageMapper.deletePackage(packageName, expectedVersion) == 1;
  }

  private SkillPackage toPackage(SkillPackageDO row) {
    if (row == null) {
      return null;
    }
    SkillPackage target = new SkillPackage();
    target.setPackageName(row.getPackageName());
    target.setDescription(row.getDescription());
    target.setRepositoryUrl(row.getRepositoryUrl());
    target.setBranch(row.getBranch());
    target.setCurrentCommit(row.getCurrentCommit());
    target.setObservedHeadCommit(row.getObservedHeadCommit());
    target.setHeadCheckedAt(row.getHeadCheckedAt());
    target.setHeadCheckError(row.getHeadCheckError());
    target.setSkills(SkillManifestJson.decode(row.getSkillsJson()));
    target.setVersion(row.getVersion() == null ? 0L : row.getVersion());
    target.setCreateTime(row.getCreateTime());
    target.setUpdateTime(row.getUpdateTime());
    return target;
  }

  private SkillPackageDO toPackageDO(SkillPackage model) {
    SkillPackageDO target = new SkillPackageDO();
    target.setPackageName(model.getPackageName());
    target.setDescription(model.getDescription());
    target.setRepositoryUrl(model.getRepositoryUrl());
    target.setBranch(model.getBranch());
    target.setCurrentCommit(model.getCurrentCommit());
    target.setObservedHeadCommit(model.getObservedHeadCommit());
    target.setHeadCheckedAt(model.getHeadCheckedAt());
    target.setHeadCheckError(model.getHeadCheckError());
    target.setSkillsJson(SkillManifestJson.encode(requireSkills(model)));
    target.setVersion(model.getVersion());
    return target;
  }

  private static List<SkillManifestEntry> requireSkills(SkillPackage model) {
    return Objects.requireNonNull(model.getSkills(), "skills");
  }
}
