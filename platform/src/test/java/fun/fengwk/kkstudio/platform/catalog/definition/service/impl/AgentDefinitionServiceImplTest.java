package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.harness.tool.HarnessToolCatalogAdapter;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;

import java.sql.SQLException;
import java.util.List;

/**
 * Agent 定义服务的契约测试：CAS 失败必须映射为 {@link AiResourceNotFoundException} / {@link
 * AiVersionConflictException} 且 deleteByName 使用解析后的 expectedVersion；创建期 FK 完整性失败映射为 not found、非 FK
 * integrity violation 原样透传；非法 model ref / variant / config 包装为 {@link AiValidationException}。
 */
public class AgentDefinitionServiceImplTest {

  @Test
  public void shouldRejectFailedRepositoryMutations() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variants = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionServiceImpl service =
        service(repository, converter, factory, resolver, variants);

    AgentDefinition definition = definition();
    AgentDefinitionCreateDTO create = create();
    when(factory.newAgent("agent", create)).thenReturn(definition);
    when(repository.create(definition)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createAgent(create));

    when(repository.create(definition)).thenThrow(new DuplicateKeyException("duplicate"));
    assertThrows(AiDuplicateException.class, () -> service.createAgent(create));
    verify(resolver, atLeastOnce()).requireModelForUpdate("provider", "model");

    AgentDefinitionUpdateDTO update = update("0");
    AgentDefinitionUpdateDTO missing = new AgentDefinitionUpdateDTO();
    // updateDTO 为 null 与缺少 expectedVersion 一样在解析阶段确定性拒绝，而不是 NPE。
    assertThrows(AiValidationException.class, () -> service.updateAgent("agent", null));
    assertThrows(AiValidationException.class, () -> service.updateAgent("agent", missing));
    when(resolver.requireAgent("agent")).thenReturn(definition);
    when(resolver.requireAgentAndSubagentsForUpdate("agent", List.of())).thenReturn(definition);
    when(repository.updateByName(definition, 0L)).thenReturn(false);
    when(repository.getByName("agent")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.updateAgent("agent", update));

    AgentDefinition reread = definition();
    reread.setVersion(7L);
    when(repository.getByName("agent")).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.updateAgent("agent", update));
  }

  @Test
  public void shouldPrioritizeStaleVersionOverModelValidation() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variants = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionServiceImpl service =
        service(repository, converter, factory, resolver, variants);
    AgentDefinition definition = definition();
    definition.setVersion(1L);
    when(resolver.requireAgent("agent")).thenReturn(definition);

    AgentDefinitionUpdateDTO update = update("0");
    assertThrows(AiVersionConflictException.class, () -> service.updateAgent("agent", update));
    verify(resolver, never()).requireModelForUpdate("provider", "model");
    verify(factory, never()).update(definition, update);
  }

  @Test
  public void shouldFailDeleteByCasAndRereadState() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variants = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionServiceImpl service =
        service(repository, converter, factory, resolver, variants);

    AgentDefinition definition = definition();
    definition.setVersion(3L);
    when(resolver.requireAgentForUpdate("agent")).thenReturn(definition);

    // 删除 CAS 失败且行已不存在：确定性 not found，deleteByName 收到解析后的 expectedVersion。
    when(repository.deleteByName("agent", 3L)).thenReturn(false);
    when(repository.getByName("agent")).thenReturn(null);
    assertThrows(AiResourceNotFoundException.class, () -> service.deleteAgent("agent", "3"));

    // 删除 CAS 失败且行版本已变化：version conflict，且 deleteByName 一直使用解析后的 expectedVersion。
    AgentDefinition reread = definition();
    reread.setVersion(7L);
    when(repository.getByName("agent")).thenReturn(reread);
    assertThrows(AiVersionConflictException.class, () -> service.deleteAgent("agent", "3"));
    verify(repository, atLeastOnce()).deleteByName("agent", 3L);

    // 删除成功路径：CAS 命中后不再 reread。
    when(repository.deleteByName("agent", 3L)).thenReturn(true);
    service.deleteAgent("agent", "3");

    // 行版本已过期时在 CAS 之前确定性拒绝，deleteByName 不被调用。
    definition.setVersion(1L);
    assertThrows(AiVersionConflictException.class, () -> service.deleteAgent("agent", "0"));
    verify(repository, never()).deleteByName(eq("agent"), eq(0L));
  }

  @Test
  public void shouldRejectInvalidCreateInputs() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variants = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionServiceImpl service =
        service(repository, converter, factory, resolver, variants);

    // 非法 model ref：在引用解析前包装为 AiValidationException。
    AgentDefinitionCreateDTO badRef = create();
    badRef.setModel("no-slash");
    assertThrows(AiValidationException.class, () -> service.createAgent(badRef));

    // createDTO 为 null：model ref 解析阶段同样确定性拒绝。
    assertThrows(AiValidationException.class, () -> service.createAgent(null));

    // 非法 variant：variantResolver 拒绝时包装为 AiValidationException。
    AgentDefinition badVariant = definition();
    badVariant.setVariant("bad-variant");
    AgentDefinitionCreateDTO create = create();
    when(factory.newAgent("agent", create)).thenReturn(badVariant);
    when(variants.resolve("provider", "model", "bad-variant"))
        .thenThrow(new IllegalArgumentException("unknown variant: bad-variant"));
    assertThrows(AiValidationException.class, () -> service.createAgent(create));

    // 非法 config：Agent 选择不存在的工具，configValidator 拒绝时包装为 AiValidationException。
    AgentDefinition badConfig = definition();
    badConfig.setConfigJson("{\"toolIds\":[\"no-such-tool\"],\"skills\":[],\"subagents\":[]}");
    when(factory.newAgent("agent", create)).thenReturn(badConfig);
    assertThrows(AiValidationException.class, () -> service.createAgent(create));
  }

  @Test
  public void shouldMapCreateIntegrityFailures() {
    AgentDefinitionRepository repository = mock(AgentDefinitionRepository.class);
    AgentDefinitionConverter converter = mock(AgentDefinitionConverter.class);
    AgentDefinitionMutationFactory factory = mock(AgentDefinitionMutationFactory.class);
    AgentDefinitionReferenceResolver resolver = mock(AgentDefinitionReferenceResolver.class);
    AgentModelDefaultVariantResolver variants = mock(AgentModelDefaultVariantResolver.class);
    AgentDefinitionServiceImpl service =
        service(repository, converter, factory, resolver, variants);

    AgentDefinition definition = definition();
    AgentDefinitionCreateDTO create = create();
    when(factory.newAgent("agent", create)).thenReturn(definition);

    // PostgreSQL FK 完整性失败：模型不存在 → 确定性 not found。
    doThrow(integrityFailure("23503")).when(repository).create(definition);
    assertThrows(AiResourceNotFoundException.class, () -> service.createAgent(create));

    // 非 FK integrity violation：原样透传，不掩盖数据库错误语义。
    DataIntegrityViolationException nonForeignKey = integrityFailure("22001");
    doThrow(nonForeignKey).when(repository).create(definition);
    assertSame(
        nonForeignKey,
        assertThrows(DataIntegrityViolationException.class, () -> service.createAgent(create)));
  }

  private AgentDefinitionServiceImpl service(
      AgentDefinitionRepository repository,
      AgentDefinitionConverter converter,
      AgentDefinitionMutationFactory factory,
      AgentDefinitionReferenceResolver resolver,
      AgentModelDefaultVariantResolver variants) {
    return new AgentDefinitionServiceImpl(
        repository,
        converter,
        factory,
        resolver,
        variants,
        new AgentDefinitionConfigValidator(
            new HarnessToolCatalogAdapter(HarnessCatalog.from(List.of()))),
        new AgentDefinitionConfigCodec(new ObjectMapper()));
  }

  private AgentDefinition definition() {
    AgentDefinition definition = new AgentDefinition();
    definition.setName("agent");
    definition.setModelProviderName("provider");
    definition.setModelName("model");
    definition.setConfigJson("{\"toolIds\":[],\"skills\":[],\"subagents\":[]}");
    definition.setVersion(0L);
    return definition;
  }

  private AgentDefinitionCreateDTO create() {
    AgentDefinitionCreateDTO create = new AgentDefinitionCreateDTO();
    create.setName("agent");
    create.setModel("provider/model");
    create.setConfig(config());
    return create;
  }

  private AgentDefinitionUpdateDTO update(String version) {
    AgentDefinitionUpdateDTO update = new AgentDefinitionUpdateDTO();
    update.setModel("provider/model");
    update.setConfig(config());
    update.setExpectedVersion(version);
    return update;
  }

  private AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setToolIds(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    return config;
  }

  private static DataIntegrityViolationException integrityFailure(String sqlState) {
    return new DataIntegrityViolationException(
        "database integrity failure", new SQLException("database failure", sqlState));
  }
}
