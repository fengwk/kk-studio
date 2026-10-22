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

import fun.fengwk.kkstudio.platform.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

/** 统一 Model / Model Group CRUD API；逻辑名称可包含斜杠。 */
@AllArgsConstructor
@RequestMapping("/api/ai/catalog/models")
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

  @PutMapping("/{*modelName}")
  public Result<AgentModelDTO> updateModel(
      @PathVariable("modelName") String modelName, @RequestBody AgentModelUpdateDTO updateDTO) {
    return Results.ok(agentModelService.updateModel(stripLeadingSlash(modelName), updateDTO));
  }

  @DeleteMapping("/{*modelName}")
  public Result<Void> deleteModel(
      @PathVariable("modelName") String modelName,
      @RequestParam("expectedVersion") String expectedVersion) {
    agentModelService.deleteModel(stripLeadingSlash(modelName), expectedVersion);
    return Results.noContent();
  }

  private static String stripLeadingSlash(String modelName) {
    return modelName != null && modelName.startsWith("/") ? modelName.substring(1) : modelName;
  }
}
