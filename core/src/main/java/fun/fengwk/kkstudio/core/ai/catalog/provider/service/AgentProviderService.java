package fun.fengwk.kkstudio.core.ai.catalog.provider.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

/** Global provider application service. */
public interface AgentProviderService {

  Page<AgentProviderDTO> pageProviders(PageQuery pageQuery);

  AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO);

  /**
   * Atomic CAS update on (name, {@code updateDTO.expectedVersion}). The expectedVersion token is
   * read from the DTO only; stale values raise {@link
   * fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException}.
   */
  AgentProviderDTO updateProvider(String name, AgentProviderUpdateDTO updateDTO);

  /** Atomic CAS delete on (name, expectedVersion). */
  void deleteProvider(String name, String expectedVersion);
}
