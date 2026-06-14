package fun.fengwk.kkstudio.agent.session.repo;

import fun.fengwk.kkstudio.agent.session.SessionEvent;

import java.util.List;

/**
 * SessionEventRepository 负责 session tree 事件持久化。
 *
 * 语义说明：
 * - repository 可以按 sessionId 做批量装载，以适配不同存储系统的高效读取方式。
 * - repository 暴露 event 级存储能力。
 *
 * @author fengwk
 */
public interface SessionEventRepository {

    List<SessionEvent> listBySessionId(String sessionId);

    void appendEvent(SessionEvent event);

}
