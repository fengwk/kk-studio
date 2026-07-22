package fun.fengwk.kkstudio.core.agent.model.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

/** Global model CRUD. */
@AllArgsConstructor
@Service
public class AgentModelServiceImpl implements AgentModelService {

  private final AgentModelRepository agentModelRepository;
  private final AgentModelConverter agentModelConverter;
  private final AgentModelMutationFactory modelMutationFactory;
  private final AgentModelReferenceResolver referenceResolver;

  @Override
  public Page<AgentModelDTO> pageModels(PageQuery pageQuery) {
    return agentModelRepository.page(pageQuery).map(agentModelConverter::convert);
  }

  @Override
  public AgentModelDTO createModel(AgentModelCreateDTO createDTO) {
    long providerId = parseId(createDTO == null ? null : createDTO.getProviderId(), "providerId");
    referenceResolver.requireProvider(providerId);
    AgentModel model = modelMutationFactory.newModel(providerId, createDTO);
    referenceResolver.ensureNameAvailable(providerId, model.getName());
    if (!agentModelRepository.create(model)) {
      throw new IllegalStateException("create agent model failed");
    }
    return agentModelConverter.convert(agentModelRepository.getById(model.getId()));
  }

  @Override
  public AgentModelDTO updateModel(long id, AgentModelUpdateDTO updateDTO) {
    AgentModel model = referenceResolver.requireModel(id);
    String currentName = model.getName();
    modelMutationFactory.update(model, updateDTO);
    // provider_id is immutable on update; uniqueness is still scoped to the owning provider.
    referenceResolver.ensureNameAvailable(model.getProviderId(), currentName, model.getName());
    if (!agentModelRepository.updateById(model)) {
      throw new IllegalStateException("update agent model failed: " + id);
    }
    return agentModelConverter.convert(agentModelRepository.getById(id));
  }

  @Override
  public void deleteModel(long id) {
    referenceResolver.requireModel(id);
    referenceResolver.ensureDeletable(id);
    if (!agentModelRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent model failed: " + id);
    }
  }

  private long parseId(String value, String field) {
    try {
      long id = Long.parseLong(value);
      if (id <= 0) {
        throw new NumberFormatException();
      }
      return id;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " must be a positive Snowflake ID", error);
    }
  }
}
