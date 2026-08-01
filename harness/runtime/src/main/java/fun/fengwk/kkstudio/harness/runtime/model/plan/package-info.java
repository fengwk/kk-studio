/**
 * 纯 {@code ModelInvocationPlanner}：根据 head entry path 与每个响应债务最近的 {@code TurnSettings} 派生 {@code
 * ModelInvocationPlan}（frozen {@code ProviderRequest}）。本包拥有 plan 输出类型 {@link
 * fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan}。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>校验 {@code rootToHead} 是合法 ordered Session Tree path（root → head，链连续）；
 *   <li>从 head 向前扫描 response debt：USER / TOOL 消息产生 debt；ASSISTANT（带 metadata）与 {@code
 *       ASSISTANT_ERROR} 是已偿还 / 失败 barrier；ROOT / SYSTEM 消息只是向前跳过（不产生 debt），但一旦 debt 落定，debt 之后的
 *       Entry 不进入 request 前缀；
 *   <li>在 debt 前缀内寻找最近的 {@code TurnSettings}，Tool result 沿 preceding user/custom turn 回溯；
 *   <li>按 {@code MessageEntryPayload} / {@code CustomMessageEntryPayload} 构造语义消息；
 *   <li>leading SYSTEM message = live system prompt + canonical skill bindings XML；
 *   <li>frozen tool definitions 按 resolved binding 顺序；
 *   <li>经 {@link fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer} 派生最终 cache
 *       control。
 * </ul>
 *
 * <p>约束：不读取 Store / Spring / live Definition / Clock；不写 Entry / head；纯函数（除注入 {@code
 * TurnExecutionResolver} 与 cache key factory）。
 */
package fun.fengwk.kkstudio.harness.runtime.model.plan;
