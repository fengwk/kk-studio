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

import fun.fengwk.kkstudio.core.workspace.service.WorkspaceService;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceUpdateDTO;

/** Workspace CRUD API. */
@AllArgsConstructor
@RequestMapping("/api/workspaces")
@RestController
public class StudioWorkspaceController {

  private final WorkspaceService workspaceService;

  @GetMapping
  public Result<Page<WorkspaceDTO>> pageWorkspaces(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
    return Results.ok(workspaceService.pageWorkspaces(new PageQuery(pageNumber, pageSize)));
  }

  @GetMapping("/{workspaceId}")
  public Result<WorkspaceDTO> getWorkspace(@PathVariable long workspaceId) {
    return Results.ok(workspaceService.getWorkspace(workspaceId));
  }

  @PostMapping
  public Result<WorkspaceDTO> createWorkspace(@RequestBody WorkspaceCreateDTO createDTO) {
    return Results.created(workspaceService.createWorkspace(createDTO));
  }

  @PutMapping("/{workspaceId}")
  public Result<WorkspaceDTO> updateWorkspace(
      @PathVariable long workspaceId, @RequestBody WorkspaceUpdateDTO updateDTO) {
    return Results.ok(workspaceService.updateWorkspace(workspaceId, updateDTO));
  }

  @DeleteMapping("/{workspaceId}")
  public Result<Void> deleteWorkspace(@PathVariable long workspaceId) {
    workspaceService.deleteWorkspace(workspaceId);
    return Results.noContent();
  }
}
