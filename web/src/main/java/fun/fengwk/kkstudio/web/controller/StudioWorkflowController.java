package fun.fengwk.kkstudio.web.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.studio.service.StudioDtoMapper;
import fun.fengwk.kkstudio.core.studio.service.StudioNotImplementedException;
import fun.fengwk.kkstudio.share.model.studio.WorkflowDocumentDTO;
import fun.fengwk.kkstudio.studio.workflow.WorkflowCommandService;
import fun.fengwk.kkstudio.studio.workflow.WorkflowQueryService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Workflow HTTP boundary. Draft/publish adapters are still stubs. */
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class StudioWorkflowController {

  private final WorkflowQueryService workflowQueryService;
  private final WorkflowCommandService workflowCommandService;

  @GetMapping
  public List<WorkflowDocumentDTO> list(@RequestParam("workspaceId") long workspaceId) {
    return workflowQueryService.listDocuments(workspaceId).stream()
        .map(StudioDtoMapper::toDto)
        .collect(Collectors.toList());
  }

  @GetMapping("/{workflowId}")
  public ResponseEntity<WorkflowDocumentDTO> get(@PathVariable("workflowId") long workflowId) {
    return workflowQueryService
        .findDocument(workflowId)
        .map(StudioDtoMapper::toDto)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping
  public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
    try {
      long workspaceId = Long.parseLong(body.get("workspaceId"));
      String name = body.getOrDefault("name", "Untitled Workflow");
      WorkflowDocumentDTO dto =
          StudioDtoMapper.toDto(workflowCommandService.createWorkflow(workspaceId, name));
      return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    } catch (StudioNotImplementedException ex) {
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(ex.getMessage());
    }
  }
}
