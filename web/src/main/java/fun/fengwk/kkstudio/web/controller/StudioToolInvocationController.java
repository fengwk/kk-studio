package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.harness.tool.service.HarnessSessionYoloService;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolDecisionConflictException;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolInvocationDecisionService;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPermissionDecision;
import fun.fengwk.kkstudio.share.model.SessionYoloDTO;
import fun.fengwk.kkstudio.share.model.SessionYoloSetDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDecisionDTO;
import java.util.ConcurrentModificationException;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Workspace-scoped Tool permission decision 与 Session YOLO API。 */
@AllArgsConstructor
@RequestMapping("/api/workspaces/{workspaceId}")
@RestController
public class StudioToolInvocationController {
  private final ToolInvocationDecisionService decisionService;
  private final HarnessSessionYoloService yoloService;

  @PostMapping("/tool-invocations/{invocationId}/decision")
  public Result<ToolInvocationDTO> decide(
      @PathVariable long workspaceId,
      @PathVariable long invocationId,
      @RequestBody ToolInvocationDecisionDTO request) {
    try {
      ToolPermissionDecision decision =
          ToolPermissionDecision.fromApiValue(request == null ? null : request.getDecision());
      return Results.ok(toDTO(decisionService.decide(workspaceId, invocationId, decision)));
    } catch (ToolDecisionConflictException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }

  @PutMapping("/sessions/{sessionId}/yolo")
  public Result<SessionYoloDTO> setYolo(
      @PathVariable long workspaceId,
      @PathVariable long sessionId,
      @RequestBody SessionYoloSetDTO request) {
    if (request == null || request.getEnabled() == null) {
      throw new IllegalArgumentException("enabled must not be null");
    }
    try {
      return Results.ok(toDTO(yoloService.set(workspaceId, sessionId, request.getEnabled())));
    } catch (ConcurrentModificationException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }

  @GetMapping("/sessions/{sessionId}/yolo")
  public Result<SessionYoloDTO> getYolo(
      @PathVariable long workspaceId, @PathVariable long sessionId) {
    return Results.ok(toDTO(yoloService.get(workspaceId, sessionId)));
  }

  private ToolInvocationDTO toDTO(ToolInvocation source) {
    ToolInvocationDTO target = new ToolInvocationDTO();
    target.setId(Long.toString(source.id()));
    target.setRunId(Long.toString(source.runId()));
    target.setOrdinal(source.ordinal());
    target.setToolCallId(source.toolCallId());
    target.setToolName(source.toolName());
    target.setStatus(source.status().name());
    target.setPermissionAction(source.permissionAction().name());
    target.setPermissionDecision(
        source.permissionDecision() == null ? null : source.permissionDecision().name());
    target.setErrorMessage(source.errorMessage());
    return target;
  }

  private SessionYoloDTO toDTO(HarnessSessionYoloService.YoloState source) {
    SessionYoloDTO target = new SessionYoloDTO();
    target.setSessionId(Long.toString(source.sessionId()));
    target.setRootSessionId(Long.toString(source.rootSessionId()));
    target.setEnabled(source.enabled());
    return target;
  }
}
