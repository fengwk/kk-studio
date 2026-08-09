package fun.fengwk.kkstudio.core.ai.runtime.task;

import fun.fengwk.kkstudio.harness.runtime.prompt.PromptTemplateLoader;

import java.util.Map;

/** task Tool 与 Agent 委派段落的严格 classpath prompt 入口。 */
final class TaskPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/core/ai/runtime/task/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();

  private TaskPrompts() {}

  static String toolDescription(int defaultMaxTurns) {
    return render("task.md", Map.of("defaultMaxTurns", Integer.toString(defaultMaxTurns)));
  }

  static String systemInstructions(int defaultMaxTurns) {
    return render("task-system.md", Map.of("defaultMaxTurns", Integer.toString(defaultMaxTurns)));
  }

  static String maxTurnsReminder() {
    return render("subagent-max-turns.md", Map.of());
  }

  private static String render(String name, Map<String, String> values) {
    return LOADER.load(ROOT + name).render(values);
  }
}
