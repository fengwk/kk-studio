package fun.fengwk.kkstudio.agent.session;

/**
 * Branch 是 session tree 上的逻辑游标。
 *
 * <p>语义说明： - branch 表示一个 session 与一个 head event 的组合。 - branch 描述一条工作链路的继续位置。 - branch event 链由
 * SessionManager 根据 headEventId 装载。
 *
 * @author fengwk
 */
public record Branch(String sessionId, String headEventId) {

  public Branch {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    if (headEventId == null || headEventId.isBlank()) {
      throw new IllegalArgumentException("headEventId must not be blank");
    }
  }

  public static Branch newBranch(String sessionId, String headEventId) {
    return new Branch(sessionId, headEventId);
  }

  public Branch next(String headEventId) {
    return new Branch(sessionId, headEventId);
  }
}
