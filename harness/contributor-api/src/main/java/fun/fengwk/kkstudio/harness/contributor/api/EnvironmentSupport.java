package fun.fengwk.kkstudio.harness.contributor.api;

/**
 * Tool 与执行环境的关系契约。
 *
 * <p>环境选择来自 Branch，而不是 Tool 自身：Platform 在每个 Turn 先恢复 Agent 选择的全部 Tool，再以「Branch 是否选择
 * Environment」做一次稳定过滤，因此同一个 Tool 在不同 Branch 上的模型可见性与执行面都由该声明决定。
 *
 * <table>
 *   <caption>支持级别语义</caption>
 *   <tr><th>级别</th><th>无 Environment</th><th>有 Environment</th></tr>
 *   <tr><td>{@link #NONE}</td><td>展示，不绑定</td><td>展示，不绑定</td></tr>
 *   <tr><td>{@link #OPTIONAL}</td><td>展示，由 Platform 侧实现执行</td><td>展示，并可使用当前 Environment</td></tr>
 *   <tr><td>{@link #REQUIRED}</td><td>不进入模型 Tool declarations</td><td>展示并绑定当前 Environment</td></tr>
 * </table>
 */
public enum EnvironmentSupport {
  /** 始终在 Platform 执行，永不接收 Environment。 */
  NONE,

  /** 无 Environment 时仍可执行，有选择时获得可选 {@link BoundEnvironment}。 */
  OPTIONAL,

  /** 只在 Branch 已选择 Environment 时进入模型工具面，并绑定该 Environment。 */
  REQUIRED
}
