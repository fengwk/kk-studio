package fun.fengwk.kkstudio.agent.session;

import fun.fengwk.kkstudio.agent.util.IdGenerator;
import lombok.Data;

/**
 * Session 表示一棵 session tree 的元数据与默认工作位置。
 *
 * 语义说明：
 * - session 持有 session tree 的元数据。
 * - currentHeadEventId 表示当前默认继续点。
 * - currentBranch() 用于派生默认 branch 游标。
 *
 * @author fengwk
 */
@Data
public class Session {

    /**
     * session 的唯一标识。
     */
    private String sessionId;

    /**
     * 当前默认继续点。
     */
    private String currentHeadEventId;

    public static Session newSession() {
        Session session = new Session();
        session.setSessionId(IdGenerator.newSessionId());
        session.setCurrentHeadEventId(SessionEvent.ROOT_EVENT_ID);
        return session;
    }

    public Branch currentBranch() {
        return Branch.newBranch(sessionId, currentHeadEventId);
    }

}
