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

import fun.fengwk.kkstudio.core.ai.catalog.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

/** provider 全局 CRUD API。 */
@AllArgsConstructor
@RequestMapping("/api/ai/catalog/providers")
@RestController
public class StudioAgentProviderController {

  private final AgentProviderService agentProviderService;

  @GetMapping
  public Result<Page<AgentProviderDTO>> pageProviders(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(agentProviderService.pageProviders(new PageQuery(pageNumber, pageSize)));
  }

  @PostMapping
  public Result<AgentProviderDTO> createProvider(@RequestBody AgentProviderCreateDTO createDTO) {
    return Results.created(agentProviderService.createProvider(createDTO));
  }

  @PutMapping("/{name}")
  public Result<AgentProviderDTO> updateProvider(
      @PathVariable String name, @RequestBody AgentProviderUpdateDTO updateDTO) {
    return Results.ok(agentProviderService.updateProvider(name, updateDTO));
  }

  @DeleteMapping("/{name}")
  public Result<Void> deleteProvider(
      @PathVariable String name, @RequestParam("expectedVersion") String expectedVersion) {
    agentProviderService.deleteProvider(name, expectedVersion);
    return Results.noContent();
  }
}
