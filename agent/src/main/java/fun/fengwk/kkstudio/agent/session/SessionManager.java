package fun.fengwk.kkstudio.agent.session;

import java.util.List;

import java.util.List;

/**
 * SessionManager 协调 session tree 的读取、追加与默认 head 更新。
 *
 * <p>语义说明： - manager 负责协调 repository 完成 session tree 读写。 - manager 对 Agent 暴露 branch 视角的访问能力。 -
 * manager 负责维护 branch 与默认 head 的一致性约束。
 *
 * @author fengwk
 */
public interface SessionManager {

  Session getSession(String sessionId);

  List<SessionEvent> loadBranchEvents(Branch branch);

  Branch appendEvent(Branch branch, SessionEvent event);

  boolean compareAndSetCurrentHeadEventId(
      String sessionId, String expectedHeadEventId, String newHeadEventId);
}
