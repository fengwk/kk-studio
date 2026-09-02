package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

/** Platform MCP server 应用服务：CRUD/CAS、refresh 与引用保护的保存流程。 */
public interface McpServerService {

  Page<McpServerDTO> pageServers(PageQuery pageQuery);

  McpServerDTO getServer(String id);

  /** 创建 server：先在事务外完成 per-call 发现与完整工具校验，再开启事务插入；发现或校验失败时 DB 完全不变。 */
  McpServerDTO createServer(McpServerCreateDTO createDTO);

  /**
   * 基于 (id, expectedVersion) 的 CAS 更新：事务外发现校验新配置，再在持有 server 行锁的事务内重查版本并原子 CAS + 原子 upsert/delete
   * 工具行。
   */
  McpServerDTO updateServer(String id, McpServerUpdateDTO updateDTO);

  /** 重新发现远端工具并原子应用；配置不变，版本 +1。 */
  McpServerDTO refreshServer(String id, String expectedVersion);

  /** 基于 (id, expectedVersion) 的硬删除 CAS：被 agent_definition.config.toolIds 引用的工具存在时整次删除失败 且 DB 不变。 */
  void deleteServer(String id, String expectedVersion);
}
