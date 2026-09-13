package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDiscoverDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerDiscoveryResponseDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

/**
 * Platform MCP server CRUD, 配置读取与发现管理 API。
 *
 * <p>安全边界：标准接口绝不返回完整配置与凭据；全量配置仅由显式 {@code GET /{id}/config} 提供并强制 {@code Cache-Control:
 * no-store}。发现请求统一返回 202 Accepted。
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

  @GetMapping("/{id}")
  public Result<McpServerDTO> getServer(@PathVariable("id") String id) {
    return Results.ok(mcpServerService.getServer(id));
  }

  @GetMapping("/{id}/config")
  public ResponseEntity<Result<McpServerConfigDTO>> getServerConfig(@PathVariable("id") String id) {
    McpServerConfigDTO config = mcpServerService.getServerConfig(id);
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(Results.ok(config));
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

  @PostMapping("/{id}/discover")
  public ResponseEntity<Result<McpServerDiscoveryResponseDTO>> discoverServer(
      @PathVariable("id") String id, @RequestBody McpServerDiscoverDTO discoverDTO) {
    String expectedVersion = discoverDTO == null ? null : discoverDTO.getExpectedVersion();
    McpServerDiscoveryResponseDTO response = mcpServerService.discoverServer(id, expectedVersion);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Results.accepted(response));
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteServer(
      @PathVariable("id") String id, @RequestParam("expectedVersion") String expectedVersion) {
    mcpServerService.deleteServer(id, expectedVersion);
    return Results.noContent();
  }
}
