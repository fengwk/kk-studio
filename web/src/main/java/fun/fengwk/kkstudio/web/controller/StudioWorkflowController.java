package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.share.model.studio.WorkflowDocumentDTO;
import fun.fengwk.kkstudio.studio.StudioFeatureNotReadyException;
import fun.fengwk.kkstudio.studio.workflow.WorkflowCommandService;
import fun.fengwk.kkstudio.studio.workflow.WorkflowQueryService;
import fun.fengwk.kkstudio.web.studio.StudioWebMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Workflow HTTP boundary. Draft/publish adapters are still stubs. Returns convention {@link
 * Result}.
 */
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class StudioWorkflowController {

  private final WorkflowQueryService workflowQueryService;
  private final WorkflowCommandService workflowCommandService;

  @GetMapping
  public Result<List<WorkflowDocumentDTO>> list(@RequestParam("workspaceId") long workspaceId) {
    return Results.ok(
        workflowQueryService.listDocuments(workspaceId).stream()
            .map(StudioWebMapper::toDto)
            .collect(Collectors.toList()));
  }

  @GetMapping("/{workflowId}")
  public Result<WorkflowDocumentDTO> get(@PathVariable("workflowId") long workflowId) {
    return workflowQueryService
        .findDocument(workflowId)
        .map(document -> Results.ok(StudioWebMapper.toDto(document)))
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "unknown workflow: " + workflowId));
  }

  @PostMapping
  public Result<WorkflowDocumentDTO> create(@RequestBody Map<String, String> body) {
    try {
      long workspaceId = Long.parseLong(body.get("workspaceId"));
      String name = body.getOrDefault("name", "Untitled Workflow");
      return Results.created(
          StudioWebMapper.toDto(workflowCommandService.createWorkflow(workspaceId, name)));
    } catch (NumberFormatException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid workspaceId", ex);
    } catch (StudioFeatureNotReadyException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
    }
  }
}
