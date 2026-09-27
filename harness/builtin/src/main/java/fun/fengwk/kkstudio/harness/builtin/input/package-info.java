/**
 * 内置人工输入工具 {@code ask_user} 的模型侧契约。
 *
 * <p>本包只提供模型可见的 {@link fun.fengwk.kkstudio.harness.builtin.input.AskUserTool}（问卷形状、说明与等待语义）与它的
 * classpath 资源入口 {@link fun.fengwk.kkstudio.harness.builtin.input.AskUserPrompts}。
 *
 * <p>关键不变量与边界：
 *
 * <ul>
 *   <li>等待由 Runtime 拥有：内置 provenance（contributor {@code builtin} + descriptor name {@code
 *       ask_user}）的调用在 READY 边界被冻结为 {@code WAITING_INPUT}，不经过权限审批与 Gateway 派发，工具实现因此从不执行真实动作；
 *   <li>问卷原文随 Assistant ToolCall 冻结、答案随 ToolResult 物化，两者的解析与校验属于 Runtime；本包不复制问卷、不保存答案，
 *       也不接受或伪造用户输入；
 *   <li>参数 schema 必须与 Runtime 的冻结问卷字段集合逐字对齐（{@code questions[].question/multiple/options[]}， 选项为
 *       {@code label/description/recommended}），因此模型产出的调用在冻结时即可解析，不产生契约漂移。
 * </ul>
 */
package fun.fengwk.kkstudio.harness.builtin.input;
