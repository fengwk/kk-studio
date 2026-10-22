package fun.fengwk.kkstudio.platform.catalog.model.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;

import java.util.List;

/** 全局 model 仓库：单 Model 与 Model Group 共用 name 主键。 */
public interface AgentModelRepository {

  Page<AgentModel> page(PageQuery pageQuery);

  /** 按 {@code name asc} 列出全部 model，用于一次性导出快照。 */
  List<AgentModel> listAll();

  AgentModel getByName(String name);

  /** 返回有效的 model 并持有其行锁，直到外层事务结束。 */
  AgentModel getByNameForUpdate(String name);

  boolean create(AgentModel model);

  /** 基于 (name, expectedVersion) 的原子 CAS 更新；GROUP 更新同时清除本组旧冷却。 */
  boolean updateByName(AgentModel model, long expectedVersion);

  /**
   * 将模型由 oldName 重命名为 model.name，并在同一事务内维护 agent_definition、模型组成员引用与相关冷却。
   * 任一步骤影响行数不符合预期均抛出运行时异常以触发外层事务回滚。
   */
  void rename(String oldName, AgentModel model, long expectedVersion);

  /** 基于 (name, expectedVersion) 的硬删除 CAS；返回 false 表示 identity 缺失或 version 不匹配。 */
  boolean deleteByName(String name, long expectedVersion);

  boolean hasAgents(String name);

  /** 是否有模型组把该名称作为成员引用。 */
  boolean hasGroupsReferencing(String name);

  /** Agent 或 Model Group 是否引用了该单 Model 的指定 Variant。 */
  boolean hasVariantReferences(String name, String variant);
}
