package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.util.List;
import java.util.Objects;

/**
 * 不可变的命令接受结果：接受后的当前 Thread projection、其所属 Session 与 Session ROOT，以及该 Thread 上与本次接受一致的 durable
 * Commands。
 *
 * <p>{@code replayed=true} 表示这是一次 exact initial creation replay / ordered replay，未写任何新行且未触碰
 * version； 否则为新接受（THREAD Work 已请求、version/next sequence 已推进）。acceptedCommands 非空且全部属于返回 Thread；
 * rootEntry 必为 Session ROOT。
 *
 * <p>{@code joinReplaced=true} 表示本次接受在同一父/子对上 supersede 了一个既有未完成 join：新 join 上位，旧 join 保留
 * invocation identity 与源命令，但不再产生独立交付。非 join 接受路径恒为 false。
 */
public record AcceptedCommands(
    Session session,
    Entry rootEntry,
    ThreadState thread,
    List<ThreadCommand> acceptedCommands,
    boolean replayed,
    boolean joinReplaced) {

  public AcceptedCommands {
    session = Objects.requireNonNull(session, "session");
    rootEntry = Objects.requireNonNull(rootEntry, "rootEntry");
    thread = Objects.requireNonNull(thread, "thread");
    acceptedCommands = List.copyOf(Objects.requireNonNull(acceptedCommands, "acceptedCommands"));
    if (acceptedCommands.isEmpty()) {
      throw new IllegalArgumentException("acceptedCommands must not be empty");
    }
    if (!thread.sessionId().equals(session.id())) {
      throw new IllegalArgumentException("thread must belong to the result session");
    }
    if (!rootEntry.sessionId().equals(session.id())) {
      throw new IllegalArgumentException("rootEntry must belong to the result session");
    }
    if (!(rootEntry.payload() instanceof RootPayload)) {
      throw new IllegalArgumentException("rootEntry must be the session ROOT entry");
    }
    long previousSequence = 0L;
    for (ThreadCommand accepted : acceptedCommands) {
      if (!accepted.threadId().equals(thread.id())) {
        throw new IllegalArgumentException(
            "acceptedCommands must all belong to the result thread: " + accepted.threadId());
      }
      if (accepted.sequence() <= previousSequence) {
        throw new IllegalArgumentException(
            "acceptedCommands sequences must be strictly increasing");
      }
      previousSequence = accepted.sequence();
    }
  }

  /** 返回同一接受结果、仅覆盖 {@code joinReplaced} 标记的新实例（供 attachJoin 记录 supersede 事实）。 */
  public AcceptedCommands withJoinReplaced(boolean replaced) {
    return new AcceptedCommands(session, rootEntry, thread, acceptedCommands, replayed, replaced);
  }
}
