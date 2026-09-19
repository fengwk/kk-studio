package fun.fengwk.kkstudio.platform.harness.skill;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.skill.SkillContentLoader;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillCatalogRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillRevision;

import java.util.Objects;

/**
 * 从 Platform 全局 Skill 目录按冻结身份精确加载正文。
 *
 * <p>精确匹配 {@code (packageName, packageVersion, name, contentRevision)} 的不可变 {@code skill_revision}
 * 行；缺失该精确 revision 时返回可操作的错误，绝不回退到当前版本的同名 Skill。
 */
@Component
public final class DatabaseSkillContentLoader implements SkillContentLoader {

  private final SkillCatalogRepository skillCatalogRepository;

  public DatabaseSkillContentLoader(SkillCatalogRepository skillCatalogRepository) {
    this.skillCatalogRepository = Objects.requireNonNull(skillCatalogRepository, "repository");
  }

  @Override
  public String load(
      String packageName, String packageVersion, String name, String contentRevision) {
    SkillRevision revision = skillCatalogRepository.getRevision(packageName, packageVersion, name);
    if (revision == null) {
      throw new IllegalArgumentException(
          "skill revision not found: "
              + packageName
              + "/"
              + packageVersion
              + "/"
              + name
              + " revision="
              + contentRevision);
    }
    if (!revision.getContentRevision().equals(contentRevision)) {
      throw new IllegalArgumentException(
          "skill revision mismatch: "
              + packageName
              + "/"
              + packageVersion
              + "/"
              + name
              + " expected="
              + contentRevision
              + " actual="
              + revision.getContentRevision());
    }
    return revision.getContent();
  }
}
