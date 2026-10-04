package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.converter.SkillCatalogConverter;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;

/**
 * 仅供其他包的单测构造真实 {@link SkillCatalogServiceImpl}：{@code validateImport} 走生产实现，其余仓储依赖以 mock 占位。
 *
 * <p>放在本包内是为了访问包级可见的 {@link SkillPackageGuard}，避免单测重复实现业务校验。
 */
public final class SkillCatalogTestFixtures {

  private SkillCatalogTestFixtures() {}

  public static SkillCatalogService realValidator() {
    return new SkillCatalogServiceImpl(
        mock(SkillPackageRepository.class),
        mock(SkillGitCache.class),
        mock(SkillCatalogConverter.class),
        mock(SkillPackageGuard.class),
        new AgentEditableSupport(new ObjectMapper()),
        mock(SkillCatalogWrites.class));
  }
}
