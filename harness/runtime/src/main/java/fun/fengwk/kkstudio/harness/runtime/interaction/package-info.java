/**
 * 待处理交互的只读投影：把等待人工输入的 {@code WAITING_INPUT} 与等待审批的 {@code WAITING_APPROVAL} ToolInvocation
 * 投影为调用方可见的一页待办，并按 {@code (createdAt, id)} 提供稳定 keyset 分页。
 *
 * <p>这里不新增持久化事实：交互的唯一事实源仍是 Invocation 行与冻结的 ToolCall 身份，投影只携带定位所需的 invocation / thread / session
 * 坐标与审批载荷，由 {@link fun.fengwk.kkstudio.harness.runtime.HarnessRuntime#listPendingInteractions}
 * 组装。产品归属与 Pane 跳转由上层按 Session/Thread 解析，本包不复制产品 owner。
 */
package fun.fengwk.kkstudio.harness.runtime.interaction;
