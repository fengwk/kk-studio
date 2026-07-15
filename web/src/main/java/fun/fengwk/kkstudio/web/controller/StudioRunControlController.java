package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.kkstudio.core.harness.control.service.HarnessRunControlCommandService;
import fun.fengwk.kkstudio.core.harness.control.service.RunControlConflictException;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlKind;
import fun.fengwk.kkstudio.harness.runtime.control.RunControlMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.share.model.RunControlCreateDTO;
import fun.fengwk.kkstudio.share.model.RunControlDTO;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 全局 Session 的 steer/follow-up 控制命令 API。 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/sessions")
public class StudioRunControlController {

  private final HarnessRunControlCommandService commandService;
  private final Clock harnessRunClock;

  @PostMapping("/{sessionId}/steer")
  public Result<RunControlDTO> steer(
      @PathVariable("sessionId") String sessionId,
      @RequestBody(required = false) RunControlCreateDTO request) {
    return submit(sessionId, request, RunControlKind.STEER);
  }

  @PostMapping("/{sessionId}/follow-ups")
  public Result<RunControlDTO> followUp(
      @PathVariable("sessionId") String sessionId,
      @RequestBody(required = false) RunControlCreateDTO request) {
    return submit(sessionId, request, RunControlKind.FOLLOW_UP);
  }

  private Result<RunControlDTO> submit(
      String sessionId, RunControlCreateDTO request, RunControlKind kind) {
    long parsed = parseSessionId(sessionId);
    AgentMessage message = buildUserMessage(request);
    RunControlMessage control;
    try {
      control = commandService.submit(parsed, kind, message, harnessRunClock.instant());
    } catch (RunControlConflictException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      if (error.getMessage() != null && error.getMessage().startsWith("unknown session")) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
      }
      throw error;
    }
    return Results.created(toDTO(control));
  }

  private long parseSessionId(String raw) {
    long parsed;
    try {
      parsed = Long.parseLong(raw);
    } catch (NumberFormatException error) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sessionId must be a positive integer: " + raw, error);
    }
    if (parsed <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sessionId must be a positive integer: " + raw);
    }
    return parsed;
  }

  private AgentMessage buildUserMessage(RunControlCreateDTO request) {
    if (request == null || request.getContent() == null || request.getContent().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
    }
    return new AgentMessage(
        AgentMessageRole.USER, List.of(new TextMessageContent(request.getContent())));
  }

  static RunControlDTO toDTO(RunControlMessage source) {
    RunControlDTO target = new RunControlDTO();
    target.setId(Long.toString(source.id()));
    target.setSessionId(Long.toString(source.sessionId()));
    target.setRunId(source.originalRunId() == null ? null : Long.toString(source.originalRunId()));
    target.setConsumedRunId(
        source.consumedRunId() == null ? null : Long.toString(source.consumedRunId()));
    target.setConsumedEntryId(
        source.consumedEntryId() == null ? null : Long.toString(source.consumedEntryId()));
    target.setKind(source.kind().name());
    target.setConsumptionMode(source.consumptionMode().name());
    target.setStatus(source.status().name());
    target.setContent(extractText(source));
    target.setCreatedAt(toLocal(source.createdAt()));
    target.setConsumedAt(toLocal(source.consumedAt()));
    return target;
  }

  private static String extractText(RunControlMessage source) {
    return source.message().contents().stream()
        .filter(c -> c instanceof TextMessageContent)
        .map(c -> ((TextMessageContent) c).text())
        .findFirst()
        .orElse(null);
  }

  private static LocalDateTime toLocal(Instant value) {
    return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
