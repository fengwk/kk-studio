package fun.fengwk.kkstudio.agent;

import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.session.Branch;
import fun.fengwk.kkstudio.agent.session.Session;
import fun.fengwk.kkstudio.agent.session.SessionEvent;
import fun.fengwk.kkstudio.agent.session.SessionEventType;
import fun.fengwk.kkstudio.agent.session.SessionManager;
import fun.fengwk.kkstudio.agent.session.payload.Payload;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventMessageProjector;
import fun.fengwk.kkstudio.agent.session.projection.SessionEventProjection;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentSessionWriter 维护当前 branch 的投影视图并串行追加事件。
 *
 * <p>session、branch、branchEvents 与由其派生的 agent/model/messages 缓存始终来自同一条
 * branch event 链。构造时建立初始投影；切换分支或追加事件后标记缓存为 dirty，在下一次读取
 * 投影数据时重新建立完整投影。
 *
 * @author fengwk
 */
final class AgentSessionWriter {

  private final Session session;
  private final SessionManager sessionManager;
  private final SessionEventMessageProjector sessionEventMessageProjector;
  private final AgentEventHandler agentEventHandler;

  private Branch branch;
  private List<SessionEvent> branchEvents;
  private SetAgentInfoPayload currentAgentInfo;
  private SetModelInfoPayload currentModelInfo;
  private List<AgentMessage> projectedMessages;
  private SessionEventProjection currentProjection;
  private boolean projectionDirty;

  AgentSessionWriter(
      Session session,
      Branch branch,
      List<SessionEvent> branchEvents,
      SessionManager sessionManager,
      SessionEventMessageProjector sessionEventMessageProjector,
      AgentEventHandler agentEventHandler) {
    this.session = requireNonNull(session, "session");
    this.branch = requireNonNull(branch, "branch");
    this.branchEvents = new ArrayList<>(requireNonNull(branchEvents, "branchEvents"));
    this.sessionManager = requireNonNull(sessionManager, "sessionManager");
    this.sessionEventMessageProjector =
        requireNonNull(sessionEventMessageProjector, "sessionEventMessageProjector");
    this.agentEventHandler = requireNonNull(agentEventHandler, "agentEventHandler");
    refreshProjection();
  }

  Session getSession() {
    return session;
  }

  Branch getBranch() {
    return branch;
  }

  List<SessionEvent> getBranchEvents() {
    return List.copyOf(branchEvents);
  }

  SetAgentInfoPayload getCurrentAgentInfo() {
    ensureProjection();
    return currentAgentInfo;
  }

  SetModelInfoPayload getCurrentModelInfo() {
    ensureProjection();
    return currentModelInfo;
  }

  List<AgentMessage> getProjectedMessages() {
    ensureProjection();
    return projectedMessages;
  }

  SessionEventProjection projection() {
    ensureProjection();
    return currentProjection;
  }

  /** 通知当前 run 的未持久化失败。 */
  void notifyFailure(Throwable error) {
    agentEventHandler.onFailure(error);
  }

  /** 切换当前 branch 视图。 */
  void switchBranch(Branch newBranch, List<SessionEvent> newBranchEvents) {
    if (newBranch == null) {
      throw new IllegalArgumentException("branch must not be null");
    }
    if (newBranchEvents == null) {
      throw new IllegalArgumentException("branchEvents must not be null");
    }
    if (!session.getSessionId().equals(newBranch.sessionId())) {
      throw new IllegalArgumentException("branch does not belong to current session");
    }
    this.branch = newBranch;
    this.branchEvents = new ArrayList<>(newBranchEvents);
    markProjectionDirty();
  }

  /** 在当前 branch 上追加一个新 event，并刷新投影。 */
  SessionEvent appendEvent(SessionEventType eventType, Payload payload) {
    if (eventType == null) {
      throw new IllegalArgumentException("eventType must not be null");
    }

    String previousHeadEventId = branch.headEventId();
    SessionEvent event =
        SessionEvent.newEvent(session.getSessionId(), eventType, previousHeadEventId, payload);
    branch = sessionManager.appendEvent(branch, event);
    branchEvents.add(event);

    if (sessionManager.compareAndSetCurrentHeadEventId(
        session.getSessionId(), previousHeadEventId, branch.headEventId())) {
      session.setCurrentHeadEventId(branch.headEventId());
    }

    markProjectionDirty();
    agentEventHandler.onEvent(event);
    return event;
  }

  private void ensureProjection() {
    if (projectionDirty) {
      refreshProjection();
    }
  }

  private void markProjectionDirty() {
    projectionDirty = true;
  }

  private void refreshProjection() {
    SessionEventProjection projection = sessionEventMessageProjector.projectForRuntime(branchEvents);
    List<AgentMessage> messages = List.copyOf(projection.messages());
    this.currentProjection =
        new SessionEventProjection(projection.agentInfo(), projection.modelInfo(), messages);
    this.currentAgentInfo = currentProjection.agentInfo();
    this.currentModelInfo = currentProjection.modelInfo();
    this.projectedMessages = messages;
    this.projectionDirty = false;
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
