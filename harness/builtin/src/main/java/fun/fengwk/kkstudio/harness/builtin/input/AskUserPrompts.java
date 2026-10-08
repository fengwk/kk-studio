package fun.fengwk.kkstudio.harness.builtin.input;

import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplate;
import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplateLoader;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;

import java.util.Map;

/** {@code ask_user} 的模型可见说明与冻结问卷参数 schema 的 classpath 资源入口。 */
final class AskUserPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/builtin/input/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private AskUserPrompts() {}

  /** 工具说明：何时提问、问卷形状与冻结语义，不携带 Parameters 段（字段说明由 schema 提供）。 */
  static String instructions() {
    return template("ask_user.md").render(Map.of());
  }

  /** 问卷参数 schema：与 Runtime 的冻结问卷 codec 字段集合逐字对齐。 */
  static InputSchema inputSchema() {
    return CODEC.decode(template("ask_user.schema.json").raw());
  }

  private static PromptTemplate template(String name) {
    return LOADER.load(ROOT + name);
  }
}
