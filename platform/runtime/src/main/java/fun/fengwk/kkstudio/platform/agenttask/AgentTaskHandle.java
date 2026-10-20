package fun.fengwk.kkstudio.platform.agenttask;

import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;

import java.util.Objects;
import java.util.UUID;

/**
 * 已接受的 Agent 任务：执行它的 Thread 与观察它的票据；{@code accepted}
 * 是接受事实（Session、ROOT、命令、是否重放、是否取代了既有票据），供需要它的调用方读取。
 */
public record AgentTaskHandle(UUID threadId, UUID ticketId, AcceptedCommands accepted) {

  public AgentTaskHandle {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(ticketId, "ticketId");
    Objects.requireNonNull(accepted, "accepted");
  }
}
