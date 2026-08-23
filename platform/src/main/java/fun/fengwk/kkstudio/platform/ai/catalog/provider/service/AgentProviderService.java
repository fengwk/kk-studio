package fun.fengwk.kkstudio.platform.ai.catalog.provider.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

/** 全局 provider 应用服务。 */
public interface AgentProviderService {

  Page<AgentProviderDTO> pageProviders(PageQuery pageQuery);

  AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO);

  /**
   * 基于 (name, {@code updateDTO.expectedVersion}) 的原子 CAS 更新。expectedVersion 令牌只从 DTO 读取；过期值抛 {@link
   * fun.fengwk.kkstudio.platform.ai.error.AiVersionConflictException}。
   */
  AgentProviderDTO updateProvider(String name, AgentProviderUpdateDTO updateDTO);

  /** 基于 (name, expectedVersion) 的原子 CAS 删除。 */
  void deleteProvider(String name, String expectedVersion);
}
