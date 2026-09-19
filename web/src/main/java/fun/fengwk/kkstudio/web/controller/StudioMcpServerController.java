package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
import fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

/**
 * Platform MCP server name-keyed CRUD、配置读取与 Streamable HTTP 发现 API。
 *
 * <p>安全边界：标准接口绝不返回 URL 与 headers；完整配置仅由显式 {@code GET /{name}/config} 提供并强制 {@code Cache-Control:
 * no-store}。
 */
@AllArgsConstructor
@RequestMapping("/api/ai/mcp-servers")
@RestController
public class StudioMcpServerController {

  private static final String NO_STORE = "no-store";

  private final McpServerService mcpServerService;

  @GetMapping
  public Result<Page<McpServerDTO>> pageServers(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(mcpServerService.pageServers(new PageQuery(pageNumber, pageSize)));
  }

  @GetMapping("/{name}")
  public Result<McpServerDTO> getServer(@PathVariable("name") String name) {
    return Results.ok(mcpServerService.getServer(name));
  }

  @GetMapping("/{name}/config")
  public ResponseEntity<Result<McpServerConfigDTO>> getServerConfig(
      @PathVariable("name") String name) {
    McpServerConfigDTO config = mcpServerService.getServerConfig(name);
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(Results.ok(config));
  }

  @PostMapping
  public Result<McpServerDTO> createServer(@RequestBody McpServerCreateDTO createDTO) {
    return Results.created(mcpServerService.createServer(createDTO));
  }

  @PutMapping("/{name}")
  public Result<McpServerDTO> updateServer(
      @PathVariable("name") String name, @RequestBody McpServerUpdateDTO updateDTO) {
    return Results.ok(mcpServerService.updateServer(name, updateDTO));
  }

  @PostMapping("/{name}/discover")
  public Result<McpServerDTO> discoverServer(
      @PathVariable("name") String name, @RequestParam("expectedVersion") String expectedVersion) {
    return Results.ok(mcpServerService.discoverServer(name, expectedVersion));
  }

  @DeleteMapping("/{name}")
  public Result<Void> deleteServer(
      @PathVariable("name") String name, @RequestParam("expectedVersion") String expectedVersion) {
    mcpServerService.deleteServer(name, expectedVersion);
    return Results.noContent();
  }
}
