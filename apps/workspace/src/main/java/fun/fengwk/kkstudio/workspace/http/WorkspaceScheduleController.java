package fun.fengwk.kkstudio.workspace.http;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.account.StudioPrincipal;
import fun.fengwk.kkstudio.workspace.WorkspaceInput;
import fun.fengwk.kkstudio.workspace.WorkspaceSchedules;
import fun.fengwk.kkstudio.workspace.dto.ScheduleDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleDraftDTO;
import fun.fengwk.kkstudio.workspace.dto.SchedulePatchDTO;
import fun.fengwk.kkstudio.workspace.dto.SchedulePreviewDTO;
import fun.fengwk.kkstudio.workspace.dto.SchedulePreviewRequestDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunDTO;
import fun.fengwk.kkstudio.workspace.dto.ScheduleRunPageDTO;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat-spaces/{spaceId}/schedules")
public class WorkspaceScheduleController {
  private final WorkspaceSchedules schedules;

  WorkspaceScheduleController(WorkspaceSchedules schedules) {
    this.schedules = schedules;
  }

  @GetMapping
  public List<ScheduleDTO> list(StudioPrincipal actor, @PathVariable UUID spaceId) {
    return schedules.list(actor, spaceId);
  }

  @PostMapping("/preview")
  public SchedulePreviewDTO preview(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestBody SchedulePreviewRequestDTO request) {
    return schedules.preview(actor, spaceId, request);
  }

  @PostMapping
  public ScheduleDTO create(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody ScheduleDraftDTO request) {
    return schedules.create(actor, spaceId, WorkspaceInput.uuid(key), request);
  }

  @PatchMapping("/{id}")
  public ScheduleDTO patch(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestBody SchedulePatchDTO request) {
    return schedules.patch(actor, spaceId, id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestParam String expectedVersion) {
    schedules.delete(actor, spaceId, id, WorkspaceInput.version(expectedVersion));
  }

  @GetMapping("/{id}/runs")
  public ScheduleRunPageDTO runs(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestParam(required = false) String before) {
    return schedules.runs(actor, spaceId, id, before);
  }

  @PostMapping("/{id}/runs")
  public ScheduleRunDTO run(
      StudioPrincipal actor,
      @PathVariable UUID spaceId,
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key) {
    return schedules.run(actor, spaceId, id, WorkspaceInput.uuid(key));
  }
}
