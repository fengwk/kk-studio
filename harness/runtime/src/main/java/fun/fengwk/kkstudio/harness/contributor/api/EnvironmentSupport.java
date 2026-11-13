package fun.fengwk.kkstudio.harness.contributor.api;

/**
 * Tool 与执行环境的关系契约。
 *
 * <p>环境选择来自 Branch，而不是 Tool 自身：Platform 在每个 Turn 恢复 Agent 选择的全部 Tool；具体工具在无 Environment
 * 时通过执行结果说明是否仍可用。
 *
 * <table>
 *   <caption>支持级别语义</caption>
 *   <tr><th>级别</th><th>无 Environment</th><th>有 Environment</th></tr>
 *   <tr><td>{@link #NONE}</td><td>展示，不绑定</td><td>展示，不绑定</td></tr>
 *   <tr><td>{@link #ROUTED}</td><td>展示，不绑定；资源 URI 读取仍可执行</td><td>展示；按每次调用的参数决定执行位置</td></tr>
 *   <tr><td>{@link #REQUIRED}</td><td>展示；调用返回需要 Environment 的未执行结果</td><td>展示并绑定当前 Environment</td></tr>
 * </table>
 *
 * <p>文件工具声明 {@link #ROUTED}：工具层面不固定执行位置，{@code read} 按 {@code path} 在平台资源读取与绑定环境间分流；{@link
 * #REQUIRED} 只用于 {@code bash} 与 LSP 这类只能出现在环境上的工具。
 */
public enum EnvironmentSupport {
  /** 始终在 Platform 执行，永不接收 Environment。 */
  NONE,

  /** 每次调用按参数决定执行位置；无 Environment 时仍可执行，有选择时可使用当前 Environment。 */
  ROUTED,

  /** 只能在 Branch 已选择的 Environment 上执行；无选择时调用返回 needs-Environment 未执行结果。 */
  REQUIRED
}
