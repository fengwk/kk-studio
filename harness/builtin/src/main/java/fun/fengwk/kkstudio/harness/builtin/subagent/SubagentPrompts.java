package fun.fengwk.kkstudio.harness.builtin.subagent;

import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplate;
import fun.fengwk.kkstudio.harness.common.prompt.PromptTemplateLoader;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;

import java.util.Map;

/** Task Tool 与 Agent 委派段落的严格 classpath prompt 入口。 */
public final class SubagentPrompts {

  private static final String ROOT = "fun/fengwk/kkstudio/harness/builtin/subagent/prompts/";
  private static final PromptTemplateLoader LOADER = new PromptTemplateLoader();
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private SubagentPrompts() {}

  /** task Tool 描述：不携带 Parameters 段，字段说明由 schema 提供。 */
  public static String taskToolDescription() {
    return render("task.md", Map.of());
  }

  /** task 参数 schema：稳定声明运行期 policy 默认值语义，不嵌入具体配置快照。 */
  public static InputSchema taskInputSchema() {
    return CODEC.decode(render("task.schema.json", Map.of()));
  }

  /** Agent 正文委派段落：{@code <available_subagents>} 外壳由调用方在渲染后拼接。 */
  public static String systemInstructions(int defaultMaxTurns) {
    return render("task-system.md", Map.of("defaultMaxTurns", Integer.toString(defaultMaxTurns)));
  }

  /** maxTurns 软预算到期的 system reminder。 */
  public static String maxTurnsReminder() {
    return render("subagent-max-turns.md", Map.of());
  }

  /** skills 段落外壳模板。 */
  public static PromptTemplate agentSkillsTemplate() {
    return template("agent-skills.md");
  }

  /** subagents 段落外壳模板。 */
  public static PromptTemplate agentSubagentsTemplate() {
    return template("agent-subagents.md");
  }

  /** current_environment 外壳；有值字段列表由调用方拼入 {@code fields}。 */
  public static PromptTemplate currentEnvironmentTemplate() {
    return template("current-environment.md");
  }

  private static PromptTemplate template(String name) {
    return LOADER.load(ROOT + name);
  }

  private static String render(String name, Map<String, String> values) {
    return template(name).render(values);
  }
}
