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
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

/** Agent definition 全局 CRUD API。 */
@AllArgsConstructor
@RequestMapping("/api/agents")
@RestController
public class StudioAgentDefinitionController {

  private final AgentDefinitionService agentDefinitionService;

  @GetMapping
  public ApiResponse<Page<AgentDefinitionDTO>> pageAgents(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return ApiResponse.ok(
        agentDefinitionService.pageAgents(PageQueries.create(pageNumber, pageSize)));
  }

  @PostMapping
  public ApiResponse<AgentDefinitionDTO> createAgent(
      @RequestBody AgentDefinitionCreateDTO createDTO) {
    return ApiResponse.created(agentDefinitionService.createAgent(createDTO));
  }

  @PutMapping("/{name}")
  public ApiResponse<AgentDefinitionDTO> updateAgent(
      @PathVariable("name") String name, @RequestBody AgentDefinitionUpdateDTO updateDTO) {
    return ApiResponse.ok(agentDefinitionService.updateAgent(name, updateDTO));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> deleteAgent(
      @PathVariable("name") String name, @RequestParam("expectedVersion") String expectedVersion) {
    agentDefinitionService.deleteAgent(name, expectedVersion);
    return ApiResponse.noContent();
  }
}
