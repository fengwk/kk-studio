package fun.fengwk.kkstudio.platform.catalog.provider.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.catalog.provider.service.model.AgentProvider;

import java.util.List;

/** 全局 provider 仓库。 */
public interface AgentProviderRepository {

  Page<AgentProvider> page(PageQuery pageQuery);

  /** 按 {@code name asc} 列出全部 provider（含凭据），用于一次性导出快照。 */
  List<AgentProvider> listAll();

  AgentProvider getByName(String name);

  /** 返回有效的 provider 并持有其行锁，直到外层事务结束。 */
  AgentProvider getByNameForUpdate(String name);

  boolean create(AgentProvider provider);

  /** 基于 (name, expectedVersion) 的原子 CAS 更新。行被更新且版本恰好 +1 时返回 true；name 不存在或版本不再匹配时返回 false。 */
  boolean updateByName(AgentProvider provider, long expectedVersion);

  /** 基于 (name, expectedVersion) 的硬删除 CAS：行物理删除后同名立即可重建；返回 false 表示 name 缺失或 version 不匹配。 */
  boolean deleteByName(String name, long expectedVersion);

  boolean hasModels(String providerName);
}
