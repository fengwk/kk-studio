package fun.fengwk.kkstudio.platform.catalog.http;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.dto.McpServerCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerDiscoverDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerSummaryDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.McpServerUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerService;

import java.util.List;

/**
 * Platform MCP server 的 name-keyed CRUD 与 Streamable HTTP 发现 API。
 *
 * <p>安全边界：列表只返回安全元数据；详情返回 URL，headers 只以「已配置」事实出现，任何响应都不回显 header 值。
 */
@AllArgsConstructor
@RequestMapping("/api/platform/mcp-servers")
@RestController
public class McpServerController {

  private final McpServerService mcpServerService;

  @GetMapping
  public List<McpServerSummaryDTO> listServers() {
    return mcpServerService.listServers();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public McpServerDTO createServer(@RequestBody McpServerCreateDTO createDTO) {
    return mcpServerService.createServer(createDTO);
  }

  @GetMapping("/{name}")
  public McpServerDTO getServer(@PathVariable("name") String name) {
    return mcpServerService.getServer(name);
  }

  @PutMapping("/{name}")
  public McpServerDTO updateServer(
      @PathVariable("name") String name, @RequestBody McpServerUpdateDTO updateDTO) {
    return mcpServerService.updateServer(name, updateDTO);
  }

  @PostMapping("/{name}/discover")
  public McpServerDTO discoverServer(
      @PathVariable("name") String name, @RequestBody McpServerDiscoverDTO discoverDTO) {
    return mcpServerService.discoverServer(name, discoverDTO);
  }

  @DeleteMapping("/{name}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteServer(
      @PathVariable("name") String name, @RequestParam("expectedVersion") long expectedVersion) {
    mcpServerService.deleteServer(name, expectedVersion);
  }
}
