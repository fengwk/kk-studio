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

import fun.fengwk.kkstudio.core.agent.provider.service.AgentProviderService;
import fun.fengwk.kkstudio.share.model.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderDTO;
import fun.fengwk.kkstudio.share.model.AgentProviderUpdateDTO;

/** Global provider CRUD API. */
@AllArgsConstructor
@RequestMapping("/api/providers")
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

  @PutMapping("/{id}")
  public Result<AgentProviderDTO> updateProvider(
      @PathVariable long id, @RequestBody AgentProviderUpdateDTO updateDTO) {
    return Results.ok(agentProviderService.updateProvider(id, updateDTO));
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteProvider(
      @PathVariable long id, @RequestParam("expectedVersion") String expectedVersion) {
    agentProviderService.deleteProvider(id, expectedVersion);
    return Results.noContent();
  }
}
