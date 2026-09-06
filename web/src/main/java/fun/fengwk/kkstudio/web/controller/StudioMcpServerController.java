package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpServerService;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerRefreshDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

/**
 * Platform MCP server CRUD API。
 *
 * <p>所有路径 / DTO 边界上的 id 都是 canonical UUID string（应用侧生成），由服务层内部严格解析。响应绝不包含 bearer token；refresh 的
 * expectedVersion 走请求体 JSON，delete 的 expectedVersion 走 query 参数。
 */
@AllArgsConstructor
@RequestMapping("/api/ai/mcp-servers")
@RestController
public class StudioMcpServerController {

  private final McpServerService mcpServerService;

  @GetMapping
  public Result<Page<McpServerDTO>> pageServers(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(mcpServerService.pageServers(new PageQuery(pageNumber, pageSize)));
  }

  @GetMapping("/{id}")
  public Result<McpServerDTO> getServer(@PathVariable("id") String id) {
    return Results.ok(mcpServerService.getServer(id));
  }

  @PostMapping
  public Result<McpServerDTO> createServer(@RequestBody McpServerCreateDTO createDTO) {
    return Results.created(mcpServerService.createServer(createDTO));
  }

  @PutMapping("/{id}")
  public Result<McpServerDTO> updateServer(
      @PathVariable("id") String id, @RequestBody McpServerUpdateDTO updateDTO) {
    return Results.ok(mcpServerService.updateServer(id, updateDTO));
  }

  @PostMapping("/{id}/refresh")
  public Result<McpServerDTO> refreshServer(
      @PathVariable("id") String id, @RequestBody McpServerRefreshDTO refreshDTO) {
    String expectedVersion = refreshDTO == null ? null : refreshDTO.getExpectedVersion();
    return Results.ok(mcpServerService.refreshServer(id, expectedVersion));
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteServer(
      @PathVariable("id") String id, @RequestParam("expectedVersion") String expectedVersion) {
    mcpServerService.deleteServer(id, expectedVersion);
    return Results.noContent();
  }
}
