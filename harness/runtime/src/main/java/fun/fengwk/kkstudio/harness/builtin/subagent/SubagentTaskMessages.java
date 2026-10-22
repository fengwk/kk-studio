package fun.fengwk.kkstudio.harness.builtin.subagent;

import java.util.Objects;
import java.util.UUID;

/**
 * task 工具的唯一即时回执。完成消息由 Runtime 按固定 join receipt 渲染。
 *
 * <p>回执是英文自然语言而不是裸 JSON：先给出稳定的 {@code thread_id}，再说明子代理异步完成/失败/取消、父应继续独立工作或在无剩余工作时让出，以及用 {@code
 * task(thread_id, subagent_type, prompt)} 继续该子线程的方式。结构化元数据由 ToolResult.details 单独承载，绝不伪装成完成结果或复述
 * prompt。
 */
public final class SubagentTaskMessages {

  private SubagentTaskMessages() {}

  /** task 被持久接受后的即时回执正文，显式携带 child thread_id 与异步/继续说明。 */
  public static String accepted(UUID childThreadId) {
    return accepted(childThreadId, false);
  }

  /**
   * task 被持久接受后的即时回执正文。
   *
   * @param childThreadId 子 Thread UUID
   * @param replaced 本次续接是否取代了同一子线程上尚未完成的旧委派等待；为 true 时明确说明只会有最新一次的一份汇总结果
   */
  public static String accepted(UUID childThreadId, boolean replaced) {
    Objects.requireNonNull(childThreadId, "childThreadId");
    StringBuilder message =
        new StringBuilder("Task accepted. thread_id: ")
            .append(childThreadId)
            .append(".\n")
            .append(
                "The subagent runs asynchronously in an isolated session and reports back later as a"
                    + " separate message; it can complete, fail, or be cancelled, so do not wait or"
                    + " poll for it.\n")
            .append("Continue other independent work now, or yield if none remains.\n")
            .append("To continue this subagent later, call task with thread_id ")
            .append(childThreadId)
            .append(", subagent_type, and prompt.");
    if (replaced) {
      message.append(
          "\nThis continuation replaces the previous pending wait on this subagent thread; only one"
              + " consolidated result will be reported for the latest prompt.");
    }
    return message.toString();
  }
}
