package fun.fengwk.kkstudio.platform.agenttask;

import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinReceipt;

import java.util.Objects;
import java.util.UUID;

/** Agent 任务票据的当前状态投影：有效执行未结束或旧票据已被接管时无回执；其余状态携带冻结结果。 */
public record AgentTaskStatus(
    UUID ticketId, UUID threadId, String agent, State state, ThreadJoinReceipt receipt) {

  /** RUNNING 是有效的未完成票据；SUPERSEDED 是已被后续任务接管的终态，不伪造执行结果；其余与回执终态一一对应。 */
  public enum State {
    RUNNING,
    SUPERSEDED,
    COMPLETED,
    ERROR,
    CANCELLED
  }

  public AgentTaskStatus {
    Objects.requireNonNull(ticketId, "ticketId");
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(state, "state");
    if ((state == State.RUNNING || state == State.SUPERSEDED) != (receipt == null)) {
      throw new IllegalArgumentException("only a result-bearing terminal state has a receipt");
    }
  }
}
