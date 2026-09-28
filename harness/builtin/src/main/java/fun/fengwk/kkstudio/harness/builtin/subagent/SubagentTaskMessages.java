package fun.fengwk.kkstudio.harness.builtin.subagent;

import java.util.Objects;
import java.util.UUID;

/** task 工具的唯一即时回执。完成消息由 Runtime 按固定 join receipt 渲染。 */
public final class SubagentTaskMessages {

  private SubagentTaskMessages() {}

  public static String accepted(UUID childThreadId) {
    Objects.requireNonNull(childThreadId, "childThreadId");
    return "{\"thread_id\":\"" + childThreadId + "\",\"status\":\"accepted\"}";
  }
}
