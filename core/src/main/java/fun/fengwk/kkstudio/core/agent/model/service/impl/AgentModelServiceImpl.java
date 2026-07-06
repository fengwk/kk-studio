package fun.fengwk.kkstudio.core.agent.model.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
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
  private final AgentModelConverter agentModelConverter;
  private final AgentModelMutationFactory modelMutationFactory;
  private final AgentModelReferenceResolver referenceResolver;

  @Override
  public Page<AgentModelDTO> pageModels(PageQuery pageQuery) {
    return agentModelRepository
        .page(pageQuery)
        .map(
            model ->
                agentModelConverter.convert(
                    model, referenceResolver.resolveProvider(model.getProviderId())));
  }

  @Override
  public AgentModelDTO createModel(AgentModelCreateDTO createDTO) {
    AgentModelMutationFactory.Mutation mutation = modelMutationFactory.newCreateMutation(createDTO);
    AgentProvider provider = referenceResolver.requireProviderByName(mutation.providerName());
    referenceResolver.ensureNameAvailable(provider.getId(), mutation.name());
    AgentModel model = modelMutationFactory.newModel(provider.getId(), mutation);
    if (!agentModelRepository.create(model)) {
      throw new IllegalStateException("create agent model failed");
    }
    return agentModelConverter.convert(agentModelRepository.getById(model.getId()), provider);
  }

  @Override
  public AgentModelDTO updateModel(long id, AgentModelUpdateDTO updateDTO) {
    AgentModel existing = referenceResolver.requireModel(id);
    AgentProvider provider = referenceResolver.requireProvider(existing.getProviderId());
    AgentModelMutationFactory.Mutation mutation =
        modelMutationFactory.newUpdateMutation(existing.getName(), updateDTO);
    referenceResolver.ensureNameAvailable(
        existing.getProviderId(), existing.getName(), mutation.name());
    modelMutationFactory.apply(existing, mutation);
    if (!agentModelRepository.updateById(existing)) {
      throw new IllegalStateException("update agent model failed: " + id);
    }
    return agentModelConverter.convert(agentModelRepository.getById(id), provider);
  }

  @Override
  public void deleteModel(long id) {
    referenceResolver.requireModel(id);
    referenceResolver.ensureDeletable(id);
    if (!agentModelRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent model failed: " + id);
    }
  }
}
