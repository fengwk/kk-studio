package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;

import java.util.List;

/** Agent service CAS and composite model reference behavior. */
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
    when(factory.newAgent("agent", "provider", "model", create)).thenReturn(definition);
    when(repository.create(definition)).thenReturn(false);
    assertThrows(IllegalStateException.class, () -> service.createAgent(create));

    when(repository.create(definition)).thenThrow(new DuplicateKeyException("duplicate"));
    assertThrows(AiDuplicateException.class, () -> service.createAgent(create));
    verify(resolver, atLeastOnce()).requireModelForUpdate("provider", "model");

    AgentDefinitionUpdateDTO update = update("0");
    AgentDefinitionUpdateDTO missing = new AgentDefinitionUpdateDTO();
    assertThrows(AiValidationException.class, () -> service.updateAgent("agent", missing));
    when(resolver.requireAgent("agent")).thenReturn(definition);
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
    verify(resolver, never()).requireModel("provider", "model");
    verify(factory, never()).update(definition, update);
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
        new AgentDefinitionConfigValidator(mock(ToolCatalog.class)),
        new AgentDefinitionConfigCodec(new ObjectMapper()));
  }

  private AgentDefinition definition() {
    AgentDefinition definition = new AgentDefinition();
    definition.setName("agent");
    definition.setModelProviderName("provider");
    definition.setModelName("model");
    definition.setConfigJson("{\"tools\":[],\"skills\":[]}");
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
    update.setConfig(config());
    update.setExpectedVersion(version);
    return update;
  }

  private AgentDefinitionConfigDTO config() {
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    return config;
  }
}
