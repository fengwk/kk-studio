package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
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

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
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

/**
 * @author fengwk
 */
@AllArgsConstructor
@RequestMapping("/api/agent/agents")
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

  @PutMapping("/{id}")
  public Result<AgentDefinitionDTO> updateAgent(
      @PathVariable("id") long id, @RequestBody AgentDefinitionUpdateDTO updateDTO) {
    return Results.ok(agentDefinitionService.updateAgent(id, updateDTO));
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteAgent(@PathVariable("id") long id) {
    agentDefinitionService.deleteAgent(id);
    return Results.noContent();
  }
}
