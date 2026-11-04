package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.mockito.Mockito.mock;

import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

/**
 * 仅供其他包的单测构造真实 {@link SkillCatalogService}：{@code validateImport} 走生产实现，其余依赖以 mock 占位，避免单测重复实现业务校验。
 */
public final class SkillCatalogTestFixtures {

  private SkillCatalogTestFixtures() {}

  public static SkillCatalogService realValidator() {
    return new SkillCatalogService(
        mock(SkillPackageRepository.class),
        mock(AgentDefinitionRepository.class),
        mock(SkillGitRepository.class),
        new AgentEditableSupport(JsonMapper.builder().build()),
        mock(SkillCatalogWrites.class),
        mock(StorageUploadService.class),
        mock(StorageBlobContentService.class),
        mock(StorageBlobManager.class),
        mock(StorageBlobRepository.class),
        mock(EnvironmentRegistry.class));
  }
}
