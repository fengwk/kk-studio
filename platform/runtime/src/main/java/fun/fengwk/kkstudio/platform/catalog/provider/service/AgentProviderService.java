package fun.fengwk.kkstudio.platform.catalog.provider.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

/** 全局 provider 应用服务。 */
public interface AgentProviderService {

  Page<AgentProviderDTO> pageProviders(PageQuery pageQuery);

  AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO);

  /**
   * 基于 (name, {@code updateDTO.expectedVersion}) 的原子 CAS 更新。expectedVersion 令牌只从 DTO 读取；过期值抛 {@link
   * fun.fengwk.kkstudio.platform.error.AiVersionConflictException}。
   */
  AgentProviderDTO updateProvider(String name, AgentProviderUpdateDTO updateDTO);

  /**
   * 按名称的导入式 upsert：同名已存在时以文件事实整体覆盖，不存在时创建。
   *
   * <p>与普通 {@link #updateProvider} 的区别是凭据语义：普通更新把 null/空白视为“保留原值”，导入则视为“当前无凭据”并清空，从而
   * 保证导出快照与导入结果一致。创建/唯一约束/CAS 与普通 CRUD 完全一致。
   */
  AgentProviderDTO importProvider(String name, AgentProviderEditablePropertiesDTO properties);

  /** 基于 (name, expectedVersion) 的原子 CAS 删除。 */
  void deleteProvider(String name, String expectedVersion);
}
