package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentDefinitionServiceImpl implements AgentDefinitionService {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConverter agentDefinitionConverter;
  private final AgentDefinitionMutationFactory definitionMutationFactory;
  private final AgentDefinitionReferenceResolver referenceResolver;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return agentDefinitionRepository
        .page(pageQuery)
        .map(
            agent ->
                agentDefinitionConverter.convert(
                    agent,
                    referenceResolver.resolveProvider(agent.getDefaultProviderId()),
                    referenceResolver.resolveModel(agent.getDefaultModelId())));
  }

  @Override
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    AgentDefinitionMutationFactory.Mutation mutation =
        definitionMutationFactory.newCreateMutation(createDTO);
    referenceResolver.ensureNameAvailable(mutation.name());
    AgentDefinitionReferenceResolver.Defaults defaults =
        referenceResolver.resolveDefaults(mutation.defaultProvider(), mutation.defaultModel());
    AgentDefinition agent =
        definitionMutationFactory.newAgent(
            defaults.provider().getId(), defaults.model().getId(), mutation);
    if (!agentDefinitionRepository.create(agent)) {
      throw new IllegalStateException("create agent definition failed");
    }
    return agentDefinitionConverter.convert(
        agentDefinitionRepository.getById(agent.getId()), defaults.provider(), defaults.model());
  }

  @Override
  public AgentDefinitionDTO updateAgent(long id, AgentDefinitionUpdateDTO updateDTO) {
    AgentDefinition existing = referenceResolver.requireAgent(id);
    AgentDefinitionMutationFactory.Mutation mutation =
        definitionMutationFactory.newUpdateMutation(existing.getName(), updateDTO);
    referenceResolver.ensureNameAvailable(existing.getName(), mutation.name());
    AgentDefinitionReferenceResolver.Defaults defaults =
        referenceResolver.resolveDefaults(mutation.defaultProvider(), mutation.defaultModel());
    definitionMutationFactory.apply(
        existing, defaults.provider().getId(), defaults.model().getId(), mutation);
    if (!agentDefinitionRepository.updateById(existing)) {
      throw new IllegalStateException("update agent definition failed: " + id);
    }
    return agentDefinitionConverter.convert(
        agentDefinitionRepository.getById(id), defaults.provider(), defaults.model());
  }

  @Override
  public void deleteAgent(long id) {
    referenceResolver.requireAgent(id);
    if (!agentDefinitionRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent definition failed: " + id);
    }
  }
}
