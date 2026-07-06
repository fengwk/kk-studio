package fun.fengwk.kkstudio.core.agent.provider.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.core.agent.provider.service.converter.AgentProviderConverter;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentProviderServiceImpl implements AgentProviderService {

  private final AgentProviderRepository agentProviderRepository;
  private final AgentProviderConverter agentProviderConverter;
  private final AgentProviderMutationFactory providerMutationFactory;

  @Override
  public Page<AgentProviderDTO> pageProviders(PageQuery pageQuery) {
    return agentProviderRepository.page(pageQuery).map(agentProviderConverter::convert);
  }

  @Override
  public AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO) {
    AgentProviderMutationFactory.Mutation mutation =
        providerMutationFactory.newCreateMutation(createDTO);
    if (agentProviderRepository.getByName(mutation.name()) != null) {
      throw new IllegalArgumentException("agent provider name already exists: " + mutation.name());
    }

    AgentProvider provider = providerMutationFactory.newProvider(mutation);
    if (!agentProviderRepository.create(provider)) {
      throw new IllegalStateException("create agent provider failed");
    }
    return agentProviderConverter.convert(agentProviderRepository.getById(provider.getId()));
  }

  @Override
  public AgentProviderDTO updateProvider(long id, AgentProviderUpdateDTO updateDTO) {
    AgentProvider existing = requireProvider(id);
    AgentProviderMutationFactory.Mutation mutation =
        providerMutationFactory.newUpdateMutation(existing.getName(), updateDTO);
    if (!existing.getName().equals(mutation.name())
        && agentProviderRepository.getByName(mutation.name()) != null) {
      throw new IllegalArgumentException("agent provider name already exists: " + mutation.name());
    }

    providerMutationFactory.apply(existing, mutation);
    if (!agentProviderRepository.updateById(existing)) {
      throw new IllegalStateException("update agent provider failed: " + id);
    }
    return agentProviderConverter.convert(agentProviderRepository.getById(id));
  }

  @Override
  public void deleteProvider(long id) {
    requireProvider(id);
    if (agentProviderRepository.hasModels(id)) {
      throw new IllegalStateException("agent provider in use by models: " + id);
    }
    if (agentProviderRepository.hasAgents(id)) {
      throw new IllegalStateException("agent provider in use by agents: " + id);
    }
    if (!agentProviderRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent provider failed: " + id);
    }
  }

  private AgentProvider requireProvider(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent provider id must be positive");
    }
    AgentProvider provider = agentProviderRepository.getById(id);
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + id);
    }
    return provider;
  }
}
