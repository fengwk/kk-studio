package fun.fengwk.kkstudio.harness.contributor.builtin.environment;

import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;
import java.util.Objects;

/** 加载环境能力工具的 Prompt Markdown 资源。 */
public final class EnvironmentPrompts {

  private static final String RESOURCE_PREFIX = "fun/fengwk/kkstudio/harness/prompts/tools/";

  private EnvironmentPrompts() {}

  public static String load(String fileName) {
    Objects.requireNonNull(fileName, "fileName");
    return PromptRenderer.render(RESOURCE_PREFIX + fileName, Map.of()).trim();
  }
}
