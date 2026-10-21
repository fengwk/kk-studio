package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.mockito.Mockito.mock;

import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;

/**
 * 仅供其他包的单测构造真实 {@link SkillCatalogService}：{@code validateImport} 走生产实现，其余依赖以 mock 占位，避免单测重复实现业务校验。
 */
public final class SkillCatalogTestFixtures {

  private SkillCatalogTestFixtures() {}

  public static SkillCatalogService realValidator() {
    return new SkillCatalogService(
        mock(SkillPackageRepository.class),
        mock(AgentDefinitionRepository.class),
        mock(SkillGitCache.class),
        new AgentEditableSupport(JsonMapper.builder().build()),
        mock(SkillCatalogWrites.class));
  }
}
