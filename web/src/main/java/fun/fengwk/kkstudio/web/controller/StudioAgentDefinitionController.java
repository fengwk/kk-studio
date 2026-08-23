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

import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;

/** Agent definition 全局 CRUD API。 */
@AllArgsConstructor
@RequestMapping("/api/ai/catalog/agents")
@RestController
public class StudioAgentDefinitionController {

  private final AgentDefinitionService agentDefinitionService;

  @GetMapping
  public Result<Page<AgentDefinitionDTO>> pageAgents(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(agentDefinitionService.pageAgents(new PageQuery(pageNumber, pageSize)));
  }

  @PostMapping
  public Result<AgentDefinitionDTO> createAgent(@RequestBody AgentDefinitionCreateDTO createDTO) {
    return Results.created(agentDefinitionService.createAgent(createDTO));
  }

  @PutMapping("/{name}")
  public Result<AgentDefinitionDTO> updateAgent(
      @PathVariable String name, @RequestBody AgentDefinitionUpdateDTO updateDTO) {
    return Results.ok(agentDefinitionService.updateAgent(name, updateDTO));
  }

  @DeleteMapping("/{name}")
  public Result<Void> deleteAgent(
      @PathVariable String name, @RequestParam("expectedVersion") String expectedVersion) {
    agentDefinitionService.deleteAgent(name, expectedVersion);
    return Results.noContent();
  }
}
