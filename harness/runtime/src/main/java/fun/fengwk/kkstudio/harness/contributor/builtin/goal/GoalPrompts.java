package fun.fengwk.kkstudio.harness.contributor.builtin.goal;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;

/** Goal 特性 classpath prompt / schema 资源入口。 */
final class GoalPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/prompts/tools/";
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private GoalPrompts() {}

  static String text(String name) {
    return PromptRenderer.render(ROOT + name, Map.of());
  }

  static InputSchema schema(String name) {
    return CODEC.decode(PromptRenderer.read(ROOT + name));
  }
}
