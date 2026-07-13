package fun.fengwk.kkstudio.core.agent.run.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.agent.run.repo.AgentRunRepository;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.mapper.AgentRunMapper;
import fun.fengwk.kkstudio.core.agent.run.repo.impl.model.AgentRunDO;
import fun.fengwk.kkstudio.core.agent.run.service.model.AgentRun;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentRunRepository implements AgentRunRepository {

  private final AgentRunMapper agentRunMapper;

  @Override
  public boolean add(AgentRun run) {
    if (run == null) {
      return false;
    }
    AgentRunDO runDO = convert(run);
    boolean ok = agentRunMapper.insertSelective(runDO) > 0;
    if (ok) run.setId(runDO.getId());
    return ok;
  }

  @Override
  public AgentRun getByRunId(String runId) {
    return convert(agentRunMapper.getByRunId(runId));
  }

  @Override
  public List<AgentRun> listBySessionId(String sessionId) {
    return agentRunMapper.listBySessionId(sessionId).stream()
        .map(this::convert)
        .collect(Collectors.toList());
  }

  @Override
  public boolean existsActiveBySessionId(String sessionId) {
    return agentRunMapper.countActiveBySessionId(sessionId) > 0;
  }

  @Override
  public List<AgentRun> listStaleRuns(LocalDateTime threshold) {
    return agentRunMapper.listStaleRuns(threshold).stream()
        .map(this::convert)
        .collect(Collectors.toList());
  }

  @Override
  public boolean updateStatus(
      String runId, String expectedStatus, String status, LocalDateTime updateTime) {
    return agentRunMapper.updateStatus(runId, expectedStatus, status, updateTime) > 0;
  }

  @Override
  public boolean markFailedFromActive(String runId, String status, LocalDateTime updateTime) {
    return agentRunMapper.markFailedFromActive(runId, status, updateTime) > 0;
  }

  @Override
  public int deleteBySessionId(String sessionId) {
    return agentRunMapper.deleteBySessionId(sessionId);
  }

  private AgentRunDO convert(AgentRun run) {
    if (run == null) {
      return null;
    }

    AgentRunDO runDO = new AgentRunDO();
    runDO.setId(run.getId());
    runDO.setRunId(run.getRunId());
    runDO.setSessionId(run.getSessionId());
    runDO.setTriggerEventId(run.getTriggerEventId());
    runDO.setStatus(run.getStatus());
    runDO.setCreateTime(run.getCreateTime());
    runDO.setUpdateTime(run.getUpdateTime());
    return runDO;
  }

  private AgentRun convert(AgentRunDO runDO) {
    if (runDO == null) {
      return null;
    }

    AgentRun run = new AgentRun();
    run.setId(runDO.getId());
    run.setRunId(runDO.getRunId());
    run.setSessionId(runDO.getSessionId());
    run.setTriggerEventId(runDO.getTriggerEventId());
    run.setStatus(runDO.getStatus());
    run.setCreateTime(runDO.getCreateTime());
    run.setUpdateTime(runDO.getUpdateTime());
    return run;
  }
}
