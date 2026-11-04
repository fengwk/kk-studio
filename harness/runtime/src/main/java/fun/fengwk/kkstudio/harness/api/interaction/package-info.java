/**
 * 待处理交互的公开只读投影：等待人工输入的 {@code WAITING_INPUT}、等待审批的 {@code WAITING_APPROVAL} ToolInvocation，
 * 以及按「真实执行根 + 所需环境」聚合的环境等待。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.interaction.PendingInteraction} 与其分页 {@link
 * fun.fengwk.kkstudio.harness.api.interaction.PendingInteractionPage} 携带定位一次交互所需的 invocation /
 * thread / session 坐标、冻结的 ToolCall 身份与审批载荷； {@link
 * fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWait} 与其分页 {@link
 * fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWaitPage} 按执行根与环境聚合等待项； {@link
 * fun.fengwk.kkstudio.harness.api.interaction.EnvironmentToolWait} 是单个 TOOL 调用的不聚合视图。 分页均以 {@code
 * (createdAt, id)} 升序 keyset 翻页，{@code hasMore} 由多取一条判定。
 *
 * <p>这里不持有时序或持久状态：事实源仍是 Invocation 行与冻结的 ToolCall，投影只做形状转换。产品归属与 Pane 跳转由上层按 Session / Thread
 * 解析，本包不复制 owner。交互状态值取自 {@code ToolInvocationStatus}，不在本包重复定义。
 */
package fun.fengwk.kkstudio.harness.api.interaction;
