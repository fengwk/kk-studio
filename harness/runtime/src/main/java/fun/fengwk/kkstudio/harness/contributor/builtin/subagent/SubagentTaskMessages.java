package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;
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
   * @param replaced 本次续接是否取代了同一子线程上尚未完成的先前委派等待；为 true 时明确说明只会有最新一次的一份汇总结果
   */
  public static String accepted(UUID childThreadId, boolean replaced) {
    Objects.requireNonNull(childThreadId, "childThreadId");
    return PromptRenderer.render(
            "fun/fengwk/kkstudio/harness/prompts/messages/task-accepted.md",
            Map.of("threadId", childThreadId, "replaced", replaced))
        .stripTrailing();
  }
}
