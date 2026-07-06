package fun.fengwk.kkstudio.core.agent.model.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentModelServiceImpl implements AgentModelService {

  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentModelConverter agentModelConverter;
  private final AgentModelMutationFactory modelMutationFactory;

  @Override
  public Page<AgentModelDTO> pageModels(PageQuery pageQuery) {
    return agentModelRepository
        .page(pageQuery)
        .map(
            model ->
                agentModelConverter.convert(
                    model, agentProviderRepository.getById(model.getProviderId())));
  }

  @Override
  public AgentModelDTO createModel(AgentModelCreateDTO createDTO) {
    AgentModelMutationFactory.Mutation mutation = modelMutationFactory.newCreateMutation(createDTO);
    AgentProvider provider = requireProviderByName(mutation.providerName());
    if (agentModelRepository.getByProviderIdAndName(provider.getId(), mutation.name()) != null) {
      throw new IllegalArgumentException("agent model name already exists: " + mutation.name());
    }

    AgentModel model = modelMutationFactory.newModel(provider.getId(), mutation);
    if (!agentModelRepository.create(model)) {
      throw new IllegalStateException("create agent model failed");
    }
    return agentModelConverter.convert(agentModelRepository.getById(model.getId()), provider);
  }

  @Override
  public AgentModelDTO updateModel(long id, AgentModelUpdateDTO updateDTO) {
    AgentModel existing = requireModel(id);
    AgentProvider provider = requireProviderById(existing.getProviderId());
    AgentModelMutationFactory.Mutation mutation =
        modelMutationFactory.newUpdateMutation(existing.getName(), updateDTO);
    if (!existing.getName().equals(mutation.name())
        && agentModelRepository.getByProviderIdAndName(existing.getProviderId(), mutation.name())
            != null) {
      throw new IllegalArgumentException("agent model name already exists: " + mutation.name());
    }

    modelMutationFactory.apply(existing, mutation);
    if (!agentModelRepository.updateById(existing)) {
      throw new IllegalStateException("update agent model failed: " + id);
    }
    return agentModelConverter.convert(agentModelRepository.getById(id), provider);
  }

  @Override
  public void deleteModel(long id) {
    requireModel(id);
    if (agentModelRepository.hasAgents(id)) {
      throw new IllegalStateException("agent model in use by agents: " + id);
    }
    if (!agentModelRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent model failed: " + id);
    }
  }

  private AgentModel requireModel(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent model id must be positive");
    }
    AgentModel model = agentModelRepository.getById(id);
    if (model == null) {
      throw new IllegalArgumentException("agent model not found: " + id);
    }
    return model;
  }

  private AgentProvider requireProviderByName(String providerName) {
    AgentProvider provider = agentProviderRepository.getByName(providerName);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + providerName);
    }
    return provider;
  }

  private AgentProvider requireProviderById(long providerId) {
    AgentProvider provider = agentProviderRepository.getById(providerId);
    if (provider == null) {
      throw new IllegalStateException("agent provider not found: " + providerId);
    }
    return provider;
  }
}
