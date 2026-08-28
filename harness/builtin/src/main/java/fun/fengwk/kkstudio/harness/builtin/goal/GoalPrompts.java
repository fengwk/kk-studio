package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.runtime.prompt.PromptTemplate;
import fun.fengwk.kkstudio.harness.runtime.prompt.PromptTemplateLoader;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.util.Map;

/** Goal 特性 classpath prompt / schema 资源入口。 */
final class GoalPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/builtin/goal/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();
  private static final ToolDescriptorJsonCodec CODEC = new ToolDescriptorJsonCodec();

  private GoalPrompts() {}

  static String text(String name) {
    return template(name).render(Map.of());
  }

  static ToolParamsSchema schema(String name) {
    return CODEC.decodeInputSchema(template(name).raw());
  }

  static PromptTemplate template(String name) {
    return LOADER.load(ROOT + name);
  }
}
