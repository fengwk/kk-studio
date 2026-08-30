package fun.fengwk.kkstudio.harness.builtin.goal;

import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplate;
import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplateLoader;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;

import java.util.Map;

/** Goal 特性 classpath prompt / schema 资源入口。 */
final class GoalPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/builtin/goal/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private GoalPrompts() {}

  static String text(String name) {
    return template(name).render(Map.of());
  }

  static InputSchema schema(String name) {
    return CODEC.decode(template(name).raw());
  }

  static PromptTemplate template(String name) {
    return LOADER.load(ROOT + name);
  }
}
