package fun.fengwk.kkstudio.agent.session.repo;

import fun.fengwk.kkstudio.agent.session.Session;

/**
 * SessionRepository 负责 session 元数据持久化。
 *
 * 语义说明：
 * - repository 提供 session 元数据读取能力。
 * - repository 提供默认 head 的 CAS 更新能力。
 *
 * @author fengwk
 */
public interface SessionRepository {

    Session getSession(String sessionId);

    boolean compareAndSetCurrentHeadEventId(String sessionId, String expectedHeadEventId, String newHeadEventId);

}
