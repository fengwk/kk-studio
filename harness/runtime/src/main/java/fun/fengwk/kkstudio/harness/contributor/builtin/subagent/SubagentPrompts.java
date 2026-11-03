package fun.fengwk.kkstudio.harness.contributor.builtin.subagent;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.prompt.PromptRenderer;

import java.util.Map;

/** Task Tool 与 Agent 委派段落的严格 classpath prompt 入口。 */
public final class SubagentPrompts {

  private static final String TOOLS = "fun/fengwk/kkstudio/harness/prompts/tools/";
  private static final String SYSTEM = "fun/fengwk/kkstudio/harness/prompts/system/";
  private static final SchemaJsonCodec CODEC = new SchemaJsonCodec();

  private SubagentPrompts() {}

  /** task Tool 描述：不携带 Parameters 段，字段说明由 schema 提供。 */
  public static String taskToolDescription() {
    return PromptRenderer.render(TOOLS + "task.md", Map.of());
  }

  /** task 参数 schema：稳定声明运行期 policy 默认值语义，不嵌入具体配置快照。 */
  public static InputSchema taskInputSchema() {
    return CODEC.decode(PromptRenderer.read(TOOLS + "task.schema.json"));
  }

  /** Agent 正文委派段落：{@code <available_subagents>} 外壳由调用方在渲染后拼接。 */
  public static String systemInstructions(int defaultMaxTurns) {
    return PromptRenderer.render(
        TOOLS + "task-system.md", Map.of("defaultMaxTurns", defaultMaxTurns));
  }

  /** skills 段落外壳模板。 */
  public static String agentSkills(String skills) {
    return PromptRenderer.render(SYSTEM + "agent-skills.md", Map.of("skills", skills));
  }

  /** subagents 段落外壳模板。 */
  public static String agentSubagents(String taskInstructions, String subagents) {
    return PromptRenderer.render(
        SYSTEM + "agent-subagents.md",
        Map.of("taskInstructions", taskInstructions, "subagents", subagents));
  }

  /** current_environment 外壳；有值字段列表由调用方拼入 {@code fields}。 */
  public static String currentEnvironment(String fields) {
    return PromptRenderer.render(SYSTEM + "current-environment.md", Map.of("fields", fields));
  }
}
