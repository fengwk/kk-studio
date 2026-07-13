package fun.fengwk.kkstudio.core.agent.session.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionEventRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.mapper.AgentSessionEventMapper;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionEventDO;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionEvent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentSessionEventRepository implements AgentSessionEventRepository {

  private final AgentSessionEventMapper agentSessionEventMapper;

  @Override
  public boolean add(AgentSessionEvent sessionEvent) {
    if (sessionEvent == null) {
      return false;
    }
    AgentSessionEventDO sessionEventDO = convert(sessionEvent);
    boolean ok = agentSessionEventMapper.insertSelective(sessionEventDO) > 0;
    if (ok) sessionEvent.setId(sessionEventDO.getId());
    return ok;
  }

  @Override
  public List<AgentSessionEvent> listBySessionId(String sessionId) {
    return agentSessionEventMapper.listBySessionId(sessionId).stream()
        .map(this::convert)
        .collect(Collectors.toList());
  }

  @Override
  public List<AgentSessionEvent> listBySessionIdAfterEventId(
      String sessionId, String afterEventId) {
    if (afterEventId == null || afterEventId.isBlank()) {
      return listBySessionId(sessionId);
    }
    return agentSessionEventMapper.listBySessionIdAfterEventId(sessionId, afterEventId).stream()
        .map(this::convert)
        .collect(Collectors.toList());
  }

  @Override
  public int deleteBySessionId(String sessionId) {
    return agentSessionEventMapper.deleteBySessionId(sessionId);
  }

  private AgentSessionEventDO convert(AgentSessionEvent sessionEvent) {
    if (sessionEvent == null) {
      return null;
    }

    AgentSessionEventDO sessionEventDO = new AgentSessionEventDO();
    sessionEventDO.setId(sessionEvent.getId());
    sessionEventDO.setEventId(sessionEvent.getEventId());
    sessionEventDO.setSessionId(sessionEvent.getSessionId());
    sessionEventDO.setParentEventId(sessionEvent.getParentEventId());
    sessionEventDO.setRunId(sessionEvent.getRunId());
    sessionEventDO.setEventType(sessionEvent.getEventType());
    sessionEventDO.setPayloadJson(sessionEvent.getPayloadJson());
    sessionEventDO.setCreateTime(sessionEvent.getCreateTime());
    return sessionEventDO;
  }

  private AgentSessionEvent convert(AgentSessionEventDO sessionEventDO) {
    if (sessionEventDO == null) {
      return null;
    }

    AgentSessionEvent sessionEvent = new AgentSessionEvent();
    sessionEvent.setId(sessionEventDO.getId());
    sessionEvent.setEventId(sessionEventDO.getEventId());
    sessionEvent.setSessionId(sessionEventDO.getSessionId());
    sessionEvent.setParentEventId(sessionEventDO.getParentEventId());
    sessionEvent.setRunId(sessionEventDO.getRunId());
    sessionEvent.setEventType(sessionEventDO.getEventType());
    sessionEvent.setPayloadJson(sessionEventDO.getPayloadJson());
    sessionEvent.setCreateTime(sessionEventDO.getCreateTime());
    return sessionEvent;
  }
}
