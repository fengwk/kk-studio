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

import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

/** Global model CRUD API. */
@AllArgsConstructor
@RequestMapping("/api/models")
@RestController
public class StudioAgentModelController {

  private final AgentModelService agentModelService;

  @GetMapping
  public Result<Page<AgentModelDTO>> pageModels(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(agentModelService.pageModels(new PageQuery(pageNumber, pageSize)));
  }

  @PostMapping
  public Result<AgentModelDTO> createModel(@RequestBody AgentModelCreateDTO createDTO) {
    return Results.created(agentModelService.createModel(createDTO));
  }

  @PutMapping("/{id}")
  public Result<AgentModelDTO> updateModel(
      @PathVariable long id, @RequestBody AgentModelUpdateDTO updateDTO) {
    return Results.ok(agentModelService.updateModel(id, updateDTO));
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteModel(@PathVariable long id) {
    agentModelService.deleteModel(id);
    return Results.noContent();
  }
}
