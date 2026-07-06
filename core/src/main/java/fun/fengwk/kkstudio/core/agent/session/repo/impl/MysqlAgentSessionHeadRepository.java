package fun.fengwk.kkstudio.core.agent.session.repo.impl;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.mapper.AgentSessionHeadMapper;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionHeadDO;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionHeadRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.mapper.AgentSessionHeadMapper;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionHeadDO;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSessionHead;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentSessionHeadRepository implements AgentSessionHeadRepository {

  private final AgentSessionHeadMapper agentSessionHeadMapper;

  @Override
  public boolean add(AgentSessionHead sessionHead) {
    if (sessionHead == null) {
      return false;
    }
    AgentSessionHeadDO sessionHeadDO = convert(sessionHead);
    boolean ok = agentSessionHeadMapper.insertSelective(sessionHeadDO) > 0;
    if (ok) sessionHead.setId(sessionHeadDO.getId());
    return ok;
  }

  @Override
  public List<AgentSessionHead> listBySessionId(String sessionId) {
    return agentSessionHeadMapper.listBySessionId(sessionId).stream()
        .map(this::convert)
        .collect(Collectors.toList());
  }

  @Override
  public boolean updateHeadEventId(String sessionId, String headName, String headEventId) {
    return agentSessionHeadMapper.updateHeadEventId(sessionId, headName, headEventId) > 0;
  }

  private AgentSessionHeadDO convert(AgentSessionHead sessionHead) {
    if (sessionHead == null) {
      return null;
    }

    AgentSessionHeadDO sessionHeadDO = new AgentSessionHeadDO();
    sessionHeadDO.setId(sessionHead.getId());
    sessionHeadDO.setHeadId(sessionHead.getHeadId());
    sessionHeadDO.setSessionId(sessionHead.getSessionId());
    sessionHeadDO.setHeadName(sessionHead.getHeadName());
    sessionHeadDO.setHeadEventId(sessionHead.getHeadEventId());
    return sessionHeadDO;
  }

  private AgentSessionHead convert(AgentSessionHeadDO sessionHeadDO) {
    if (sessionHeadDO == null) {
      return null;
    }

    AgentSessionHead sessionHead = new AgentSessionHead();
    sessionHead.setHeadId(sessionHeadDO.getHeadId());
    sessionHead.setSessionId(sessionHeadDO.getSessionId());
    sessionHead.setHeadName(sessionHeadDO.getHeadName());
    sessionHead.setHeadEventId(sessionHeadDO.getHeadEventId());
    return sessionHead;
  }
}
