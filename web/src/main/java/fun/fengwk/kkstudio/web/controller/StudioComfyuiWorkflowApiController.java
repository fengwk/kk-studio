package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.ComfyuiWorkflowApiService;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiUpdateDTO;

import java.util.NoSuchElementException;

/**
 * ComfyUI 工作流卡片 CRUD 接口。
 *
 * <p>所有路径 / DTO 边界上的 id 都是 canonical UUID string（PostgreSQL uuid，由应用生成），由服务层在内部严格解析为 {@link
 * java.util.UUID} 后再访问数据库。
 *
 * <p>错误映射：malformed / nonpositive id → 400；id 解析通过但找不到记录 → 404；其它业务校验（workflowJson 非法、apiName 重复等）→
 * 400。
 *
 * @author fengwk
 */
@AllArgsConstructor
@RequestMapping("/api/comfyui/workflows")
@RestController
public class StudioComfyuiWorkflowApiController {

  private final ComfyuiWorkflowApiService comfyuiWorkflowApiService;

  @GetMapping
  public Result<Page<ComfyuiWorkflowApiDTO>> pageWorkflows(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(comfyuiWorkflowApiService.pageWorkflows(new PageQuery(pageNumber, pageSize)));
  }

  @PostMapping
  public Result<ComfyuiWorkflowApiDTO> createWorkflow(
      @RequestBody ComfyuiWorkflowApiCreateDTO createDTO) {
    return Results.created(comfyuiWorkflowApiService.createWorkflow(createDTO));
  }

  @PutMapping("/{id}")
  public Result<ComfyuiWorkflowApiDTO> updateWorkflow(
      @PathVariable("id") String id, @RequestBody ComfyuiWorkflowApiUpdateDTO updateDTO) {
    try {
      return Results.ok(comfyuiWorkflowApiService.updateWorkflow(id, updateDTO));
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    }
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteWorkflow(@PathVariable("id") String id) {
    try {
      comfyuiWorkflowApiService.deleteWorkflow(id);
      return Results.noContent();
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    }
  }
}
