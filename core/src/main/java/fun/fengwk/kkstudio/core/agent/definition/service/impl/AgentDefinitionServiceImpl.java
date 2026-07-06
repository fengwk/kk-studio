package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentDefinitionServiceImpl implements AgentDefinitionService {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentModelRepository agentModelRepository;
  private final AgentDefinitionConverter agentDefinitionConverter;
  private final AgentDefinitionMutationFactory definitionMutationFactory;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return agentDefinitionRepository
        .page(pageQuery)
        .map(
            agent ->
                agentDefinitionConverter.convert(
                    agent,
                    agentProviderRepository.getById(agent.getDefaultProviderId()),
                    agentModelRepository.getById(agent.getDefaultModelId())));
  }

  @Override
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    AgentDefinitionMutationFactory.Mutation mutation =
        definitionMutationFactory.newCreateMutation(createDTO);
    if (agentDefinitionRepository.getByName(mutation.name()) != null) {
      throw new IllegalArgumentException(
          "agent definition name already exists: " + mutation.name());
    }

    AgentProvider provider = requireProviderByName(mutation.defaultProvider());
    AgentModel model =
        requireModel(provider.getId(), mutation.defaultProvider(), mutation.defaultModel());
    AgentDefinition agent =
        definitionMutationFactory.newAgent(provider.getId(), model.getId(), mutation);
    if (!agentDefinitionRepository.create(agent)) {
      throw new IllegalStateException("create agent definition failed");
    }
    return agentDefinitionConverter.convert(
        agentDefinitionRepository.getById(agent.getId()), provider, model);
  }

  @Override
  public AgentDefinitionDTO updateAgent(long id, AgentDefinitionUpdateDTO updateDTO) {
    AgentDefinition existing = requireAgent(id);
    AgentDefinitionMutationFactory.Mutation mutation =
        definitionMutationFactory.newUpdateMutation(existing.getName(), updateDTO);
    if (!existing.getName().equals(mutation.name())
        && agentDefinitionRepository.getByName(mutation.name()) != null) {
      throw new IllegalArgumentException(
          "agent definition name already exists: " + mutation.name());
    }

    AgentProvider provider = requireProviderByName(mutation.defaultProvider());
    AgentModel model =
        requireModel(provider.getId(), mutation.defaultProvider(), mutation.defaultModel());
    definitionMutationFactory.apply(existing, provider.getId(), model.getId(), mutation);
    if (!agentDefinitionRepository.updateById(existing)) {
      throw new IllegalStateException("update agent definition failed: " + id);
    }
    return agentDefinitionConverter.convert(agentDefinitionRepository.getById(id), provider, model);
  }

  @Override
  public void deleteAgent(long id) {
    requireAgent(id);
    if (!agentDefinitionRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent definition failed: " + id);
    }
  }

  private AgentDefinition requireAgent(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent id must be positive");
    }
    AgentDefinition agent = agentDefinitionRepository.getById(id);
    if (agent == null) {
      throw new IllegalArgumentException("agent definition not found: " + id);
    }
    return agent;
  }

  private AgentProvider requireProviderByName(String providerName) {
    AgentProvider provider = agentProviderRepository.getByName(providerName);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + providerName);
    }
    return provider;
  }

  private AgentModel requireModel(long providerId, String providerName, String modelName) {
    AgentModel model = agentModelRepository.getByProviderIdAndName(providerId, modelName);
    if (model == null) {
      throw new IllegalArgumentException(
          "agent model not found: " + providerName + "/" + modelName);
    }
    return model;
  }
}
