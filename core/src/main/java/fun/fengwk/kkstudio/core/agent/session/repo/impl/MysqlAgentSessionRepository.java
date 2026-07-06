package fun.fengwk.kkstudio.core.agent.session.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.agent.session.repo.AgentSessionRepository;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.mapper.AgentSessionMapper;
import fun.fengwk.kkstudio.core.agent.session.repo.impl.model.AgentSessionDO;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlAgentSessionRepository implements AgentSessionRepository {

  private final AgentSessionMapper agentSessionMapper;

  @Override
  public Page<AgentSession> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<AgentSessionDO> result = agentSessionMapper.pageAll(offset, limit);
    long totalCount = agentSessionMapper.countAll();
    return Pages.page(pageQuery, result, totalCount).map(this::convert);
  }

  @Override
  public AgentSession getBySessionId(String sessionId) {
    return convert(agentSessionMapper.getBySessionId(sessionId));
  }

  @Override
  public boolean add(AgentSession session) {
    return agentSessionMapper.insertSelective(convert(session)) == 1;
  }

  @Override
  public boolean updateTitleBySessionId(String sessionId, String title, LocalDateTime updateTime) {
    return agentSessionMapper.updateTitleBySessionId(sessionId, title, updateTime) == 1;
  }

  @Override
  public boolean deleteBySessionId(String sessionId) {
    return agentSessionMapper.deleteBySessionId(sessionId) == 1;
  }

  @Override
  public boolean updateCurrentHeadEventId(
      String sessionId, String currentHeadEventId, LocalDateTime updateTime) {
    return agentSessionMapper.updateCurrentHeadEventId(sessionId, currentHeadEventId, updateTime)
        == 1;
  }

  @Override
  public boolean compareAndSetCurrentHeadEventId(
      String sessionId,
      String expectedCurrentHeadEventId,
      String currentHeadEventId,
      LocalDateTime updateTime) {
    return agentSessionMapper.compareAndSetCurrentHeadEventId(
            sessionId, expectedCurrentHeadEventId, currentHeadEventId, updateTime)
        == 1;
  }

  private AgentSessionDO convert(AgentSession session) {
    if (session == null) {
      return null;
    }
    AgentSessionDO sessionDO = new AgentSessionDO();
    sessionDO.setId(session.getId());
    sessionDO.setSessionId(session.getSessionId());
    sessionDO.setAgentId(session.getAgentId());
    sessionDO.setAgentName(session.getAgentName());
    sessionDO.setTitle(session.getTitle());
    sessionDO.setStatus(session.getStatus());
    sessionDO.setCurrentHeadEventId(session.getCurrentHeadEventId());
    sessionDO.setCreateTime(session.getCreateTime());
    sessionDO.setUpdateTime(session.getUpdateTime());
    return sessionDO;
  }

  private AgentSession convert(AgentSessionDO sessionDO) {
    if (sessionDO == null) {
      return null;
    }
    AgentSession session = new AgentSession();
    session.setId(sessionDO.getId());
    session.setSessionId(sessionDO.getSessionId());
    session.setAgentId(sessionDO.getAgentId());
    session.setAgentName(sessionDO.getAgentName());
    session.setTitle(sessionDO.getTitle());
    session.setStatus(sessionDO.getStatus());
    session.setCurrentHeadEventId(sessionDO.getCurrentHeadEventId());
    session.setCreateTime(sessionDO.getCreateTime());
    session.setUpdateTime(sessionDO.getUpdateTime());
    return session;
  }
}
