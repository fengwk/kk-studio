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

import fun.fengwk.kkstudio.core.agent.runtime.service.AgentRunRuntimeService;
import fun.fengwk.kkstudio.core.agent.session.service.AgentSessionService;
import fun.fengwk.kkstudio.share.model.AgentSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionEventDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentSessionUpdateDTO;

import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@RequestMapping("/api/agent/sessions")
@RestController
public class StudioAgentSessionController {

  private final AgentSessionService agentSessionService;
  private final AgentRunRuntimeService agentRunRuntimeService;

  @GetMapping
  public Result<Page<AgentSessionDTO>> pageSessions(
      @RequestParam(value = "pageNumber", defaultValue = "1") int pageNumber,
      @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
    return Results.ok(agentSessionService.pageSessions(new PageQuery(pageNumber, pageSize)));
  }

  @PostMapping
  public Result<AgentSessionDTO> createSession(@RequestBody AgentSessionCreateDTO createDTO) {
    return Results.created(agentSessionService.createSession(createDTO));
  }

  @GetMapping("/{sessionId}")
  public Result<AgentSessionDTO> getSession(@PathVariable("sessionId") String sessionId) {
    return Results.ok(agentSessionService.getSession(sessionId));
  }

  @PutMapping("/{sessionId}")
  public Result<AgentSessionDTO> updateSession(
      @PathVariable("sessionId") String sessionId, @RequestBody AgentSessionUpdateDTO updateDTO) {
    return Results.ok(agentSessionService.updateSession(sessionId, updateDTO));
  }

  @DeleteMapping("/{sessionId}")
  public Result<Void> deleteSession(@PathVariable("sessionId") String sessionId) {
    agentSessionService.deleteSession(sessionId);
    return Results.noContent();
  }

  @PostMapping("/{sessionId}/messages")
  public Result<AgentSessionEventDTO> createMessage(
      @PathVariable("sessionId") String sessionId,
      @RequestBody AgentSessionMessageCreateDTO createDTO) {
    AgentSessionEventDTO created = agentSessionService.createMessage(sessionId, createDTO);
    // agent 在另一个线程上跑，所有 event 写入都是 auto-commit，
    // 这样 SSE 端的下一个轮询周期就能拉到新增的 assistant_delta，不再被外层事务吞住。
    agentRunRuntimeService.scheduleQueuedRun(created.getRunId(), sessionId, createDTO.getContent());
    return Results.created(created);
  }

  @GetMapping("/{sessionId}/events")
  public Result<List<AgentSessionEventDTO>> listEvents(
      @PathVariable("sessionId") String sessionId,
      @RequestParam(value = "headEventId", required = false) String headEventId) {
    return Results.ok(agentSessionService.listEvents(sessionId, headEventId));
  }
}
