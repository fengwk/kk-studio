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
import fun.fengwk.kkstudio.platform.catalog.model.AgentModelService;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

/** 统一 Model / Model Group CRUD API；逻辑名称可包含斜杠。 */
@AllArgsConstructor
@RequestMapping("/api/models")
@RestController
public class StudioAgentModelController {

  private final AgentModelService agentModelService;

  @GetMapping
  public ApiResponse<Page<AgentModelDTO>> pageModels(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return ApiResponse.ok(agentModelService.pageModels(PageQueries.create(pageNumber, pageSize)));
  }

  @PostMapping
  public ApiResponse<AgentModelDTO> createModel(@RequestBody AgentModelCreateDTO createDTO) {
    return ApiResponse.created(agentModelService.createModel(createDTO));
  }

  @PutMapping("/{*modelName}")
  public ApiResponse<AgentModelDTO> updateModel(
      @PathVariable("modelName") String modelName, @RequestBody AgentModelUpdateDTO updateDTO) {
    return ApiResponse.ok(agentModelService.updateModel(stripLeadingSlash(modelName), updateDTO));
  }

  @DeleteMapping("/{*modelName}")
  public ApiResponse<Void> deleteModel(
      @PathVariable("modelName") String modelName,
      @RequestParam("expectedVersion") String expectedVersion) {
    agentModelService.deleteModel(stripLeadingSlash(modelName), expectedVersion);
    return ApiResponse.noContent();
  }

  private static String stripLeadingSlash(String modelName) {
    return modelName != null && modelName.startsWith("/") ? modelName.substring(1) : modelName;
  }
}
