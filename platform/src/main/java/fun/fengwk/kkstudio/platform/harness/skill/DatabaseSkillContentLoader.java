package fun.fengwk.kkstudio.platform.harness.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.skill.SkillContentLoader;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;

import java.util.Objects;

/**
 * 从 Platform 全局 Skill 目录按冻结身份精确加载正文。
 *
 * <p>精确匹配 {@code (packageName, packageVersion, name)} 三元组；缺失该版本时返回可操作的错误，绝不回退到当前版本的同名 Skill。
 */
@Component
public final class DatabaseSkillContentLoader implements SkillContentLoader {

  private final SkillCatalogRepository skillCatalogRepository;

  public DatabaseSkillContentLoader(SkillCatalogRepository skillCatalogRepository) {
    this.skillCatalogRepository = Objects.requireNonNull(skillCatalogRepository, "repository");
  }

  @Override
  public String load(String packageName, String packageVersion, String name) {
    Skill skill = skillCatalogRepository.getSkill(packageName, packageVersion, name);
    if (skill == null) {
      throw new IllegalArgumentException(
          "skill not found: " + packageName + "/" + packageVersion + "/" + name);
    }
    return skill.getContent();
  }
}
