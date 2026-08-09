package fun.fengwk.kkstudio.plugin.goal;

import fun.fengwk.kkstudio.harness.runtime.prompt.PromptTemplate;
import fun.fengwk.kkstudio.harness.runtime.prompt.PromptTemplateLoader;

import java.util.Map;

/** Goal 插件 classpath prompt 资源入口。 */
final class GoalPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/plugin/goal/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();

  private GoalPrompts() {}

  static String text(String name) {
    return template(name).render(Map.of());
  }

  static PromptTemplate template(String name) {
    return LOADER.load(ROOT + name);
  }
}
