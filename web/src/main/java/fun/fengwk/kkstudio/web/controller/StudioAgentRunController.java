package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.agent.run.service.AgentRunService;
import fun.fengwk.kkstudio.share.model.AgentRunDTO;

import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@RequestMapping("/api/agent/sessions/{sessionId}/runs")
@RestController
public class StudioAgentRunController {

  private final AgentRunService agentRunService;

  @GetMapping
  public Result<List<AgentRunDTO>> listRuns(@PathVariable("sessionId") String sessionId) {
    return Results.ok(agentRunService.listRuns(sessionId));
  }
}
