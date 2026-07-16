package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.environment.service.ToolEnvironmentService;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentDTO;
import fun.fengwk.kkstudio.share.model.ToolEnvironmentUpdateDTO;
import java.util.NoSuchElementException;
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

/**
 * Global Environment CRUD API.
 *
 * <p>所有路径 / DTO 边界上的 id 都是十进制字符串形式的 snowflake id，与项目内其它 snowflake 资源保持一致；服务层在内部严格解析为 {@code long}
 * 后再访问数据库。
 *
 * <p>错误映射：malformed / non-positive id → 400；id 解析通过但找不到记录 → 404；存在 {@code tool_invocation} 引用 →
 * 409；其它业务校验（name 重复、description / name 非法等）→ 400。
 *
 * <p>capabilities 和 last-seen 仅由 daemon-facing application service 写入，本控制器不接受这两个字段。
 */
@AllArgsConstructor
@RequestMapping("/api/environments")
@RestController
public class StudioToolEnvironmentController {

  private final ToolEnvironmentService toolEnvironmentService;

  @GetMapping
  public Result<Page<ToolEnvironmentDTO>> pageEnvironments(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(toolEnvironmentService.pageEnvironments(new PageQuery(pageNumber, pageSize)));
  }

  @PostMapping
  public Result<ToolEnvironmentDTO> createEnvironment(
      @RequestBody ToolEnvironmentCreateDTO createDTO) {
    return Results.created(toolEnvironmentService.createEnvironment(createDTO));
  }

  @PutMapping("/{id}")
  public Result<ToolEnvironmentDTO> updateEnvironment(
      @PathVariable("id") String id, @RequestBody ToolEnvironmentUpdateDTO updateDTO) {
    try {
      return Results.ok(toolEnvironmentService.updateEnvironment(id, updateDTO));
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    }
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteEnvironment(@PathVariable("id") String id) {
    try {
      toolEnvironmentService.deleteEnvironment(id);
      return Results.noContent();
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }
}
