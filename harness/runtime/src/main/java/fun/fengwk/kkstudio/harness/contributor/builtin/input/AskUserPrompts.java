package fun.fengwk.kkstudio.harness.contributor.builtin.input;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;

/** {@code ask_user} 的模型可见说明与冻结问卷参数 schema 的 classpath 资源入口。 */
final class AskUserPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/prompts/tools/";
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private AskUserPrompts() {}

  /** 工具说明：何时提问、问卷形状与冻结语义，不携带 Parameters 段（字段说明由 schema 提供）。 */
  static String instructions() {
    return PromptRenderer.render(ROOT + "ask_user.md", Map.of());
  }

  /** 问卷参数 schema：与 Runtime 的冻结问卷 codec 字段集合逐字对齐。 */
  static InputSchema inputSchema() {
    return CODEC.decode(PromptRenderer.read(ROOT + "ask_user.schema.json"));
  }
}
