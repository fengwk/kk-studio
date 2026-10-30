package fun.fengwk.kkstudio.web.controller;

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

import fun.fengwk.kkstudio.common.page.Page;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

import java.util.List;

/** provider 全局 CRUD API。 */
@AllArgsConstructor
@RequestMapping("/api/providers")
@RestController
public class StudioAgentProviderController {

  private final AgentProviderService agentProviderService;

  @GetMapping
  public ApiResponse<Page<AgentProviderDTO>> pageProviders(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return ApiResponse.ok(
        agentProviderService.pageProviders(PageQueries.create(pageNumber, pageSize)));
  }

  @PostMapping
  public ApiResponse<AgentProviderDTO> createProvider(
      @RequestBody AgentProviderCreateDTO createDTO) {
    return ApiResponse.created(agentProviderService.createProvider(createDTO));
  }

  @PutMapping("/{name}")
  public ApiResponse<AgentProviderDTO> updateProvider(
      @PathVariable("name") String name, @RequestBody AgentProviderUpdateDTO updateDTO) {
    return ApiResponse.ok(agentProviderService.updateProvider(name, updateDTO));
  }

  @GetMapping("/{name}/remote-models")
  public ApiResponse<List<String>> listRemoteModels(@PathVariable("name") String name) {
    return ApiResponse.ok(agentProviderService.listRemoteModelIds(name));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> deleteProvider(
      @PathVariable("name") String name, @RequestParam("expectedVersion") String expectedVersion) {
    agentProviderService.deleteProvider(name, expectedVersion);
    return ApiResponse.noContent();
  }
}
