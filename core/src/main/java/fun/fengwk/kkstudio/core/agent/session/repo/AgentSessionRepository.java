package fun.fengwk.kkstudio.core.agent.session.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import java.time.LocalDateTime;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.session.service.model.AgentSession;
import java.time.LocalDateTime;

/**
 * @author fengwk
 */
public interface AgentSessionRepository {

  Page<AgentSession> page(PageQuery pageQuery);

  boolean add(AgentSession session);

  AgentSession getBySessionId(String sessionId);

  boolean updateTitleBySessionId(String sessionId, String title, LocalDateTime updateTime);

  boolean deleteBySessionId(String sessionId);

  boolean updateCurrentHeadEventId(
      String sessionId, String currentHeadEventId, LocalDateTime updateTime);

  boolean compareAndSetCurrentHeadEventId(
      String sessionId,
      String expectedCurrentHeadEventId,
      String currentHeadEventId,
      LocalDateTime updateTime);
}
