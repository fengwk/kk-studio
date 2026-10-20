package fun.fengwk.kkstudio.platform.agenttask;

import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;

import java.util.Objects;
import java.util.UUID;

/** Agent 任务票据的当前状态投影：{@code receipt} 在任务结束前为 null，结束后是冻结结果的回执。 */
public record AgentTaskStatus(
    UUID ticketId, UUID threadId, String agent, State state, ThreadJoinReceipt receipt) {

  /** 任务状态：RUNNING 表示票据尚未冻结结果，其余与回执终态一一对应。 */
  public enum State {
    RUNNING,
    COMPLETED,
    ERROR,
    CANCELLED
  }

  public AgentTaskStatus {
    Objects.requireNonNull(ticketId, "ticketId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(state, "state");
    if ((state == State.RUNNING) != (receipt == null)) {
      throw new IllegalArgumentException("a receipt exists exactly when the task has finished");
    }
  }
}
