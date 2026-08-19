package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 不可变的命令接受结果：接受后的当前 Thread projection 与该 Thread 上与本次接受一致的 durable Commands。
 *
 * <p>{@code replayed=true} 表示这是一次 exact materialization / ordered replay，未写任何新行且未触碰 revision；
 * 否则为新接受（THREAD Work 已请求、revision/next sequence 已推进）。
 */
public record AcceptCommandsResult(
    UUID sessionId,
    UUID threadId,
    ThreadState thread,
    List<ThreadCommand> commands,
    boolean replayed) {

  public AcceptCommandsResult {
    sessionId = Objects.requireNonNull(sessionId, "sessionId");
    threadId = Objects.requireNonNull(threadId, "threadId");
    thread = Objects.requireNonNull(thread, "thread");
    commands = List.copyOf(Objects.requireNonNull(commands, "commands"));
    if (!thread.id().equals(threadId)) {
      throw new IllegalArgumentException("threadId must equal the result thread id");
    }
    if (!thread.sessionId().equals(sessionId)) {
      throw new IllegalArgumentException("sessionId must equal the result thread session");
    }
  }
}
