package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

/** Platform MCP server 应用服务：name-keyed CRUD/CAS、显式发现与引用保护流程。 */
public interface McpServerService {

  Page<McpServerDTO> pageServers(PageQuery pageQuery);

  /** 按不可变 name 获取 Server 安全公开视图。 */
  McpServerDTO getServer(String name);

  /** 读取 Server 显式 HTTP 配置；headers 可能内嵌凭据，调用方必须禁止缓存。 */
  McpServerConfigDTO getServerConfig(String name);

  /** 创建 Server 配置：仅校验并持久化为 UNVERIFIED，不发起任何网络 I/O。 */
  McpServerDTO createServer(McpServerCreateDTO createDTO);

  /** 基于 (name, expectedVersion) 的 CAS 全量更新：仅更新配置并重置状态为 UNVERIFIED，不发起发现。 */
  McpServerDTO updateServer(String name, McpServerUpdateDTO updateDTO);

  /** 基于 (name, expectedVersion) 的显式发现：同步握手并原子替换当前工具行。 */
  McpServerDTO discoverServer(String name, String expectedVersion);

  /** 基于 (name, expectedVersion) 的硬删除 CAS：被 agent 引用时拒绝删除。 */
  void deleteServer(String name, String expectedVersion);
}
