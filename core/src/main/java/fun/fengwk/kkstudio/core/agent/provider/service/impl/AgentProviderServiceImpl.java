package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentProviderServiceImpl implements AgentProviderService {

  private final AgentProviderRepository agentProviderRepository;
  private final AgentProviderConverter agentProviderConverter;
  private final AgentProviderMutationFactory providerMutationFactory;
  private final AgentProviderGuard providerGuard;

  @Override
  public Page<AgentProviderDTO> pageProviders(PageQuery pageQuery) {
    return agentProviderRepository.page(pageQuery).map(agentProviderConverter::convert);
  }

  @Override
  public AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO) {
    AgentProviderMutationFactory.Mutation mutation =
        providerMutationFactory.newCreateMutation(createDTO);
    providerGuard.ensureNameAvailable(mutation.name());
    AgentProvider provider = providerMutationFactory.newProvider(mutation);
    if (!agentProviderRepository.create(provider)) {
      throw new IllegalStateException("create agent provider failed");
    }
    return agentProviderConverter.convert(agentProviderRepository.getById(provider.getId()));
  }

  @Override
  public AgentProviderDTO updateProvider(long id, AgentProviderUpdateDTO updateDTO) {
    AgentProvider existing = providerGuard.requireProvider(id);
    AgentProviderMutationFactory.Mutation mutation =
        providerMutationFactory.newUpdateMutation(existing.getName(), updateDTO);
    providerGuard.ensureNameAvailable(existing.getName(), mutation.name());
    providerMutationFactory.apply(existing, mutation);
    if (!agentProviderRepository.updateById(existing)) {
      throw new IllegalStateException("update agent provider failed: " + id);
    }
    return agentProviderConverter.convert(agentProviderRepository.getById(id));
  }

  @Override
  public void deleteProvider(long id) {
    providerGuard.requireProvider(id);
    providerGuard.ensureDeletable(id);
    if (!agentProviderRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent provider failed: " + id);
    }
  }
}
