/**
 * 纯 {@code ModelInvocationPlanner}：根据 head entry path 与最近 {@code RuntimeConfigSnapshot} 派生 {@code
 * ModelInvocationPlan}（frozen {@code ProviderRequest}）。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>校验 {@code rootToHead} 是合法 ordered Session Tree path（root → head，sessionId 一致，链连续）；
 *   <li>从 head 向前扫描 response debt：USER / TOOL / COMPACTION 产生 debt；ASSISTANT（带 metadata）与
 *       ASSISTANT_ERROR 是已偿还 / 失败 barrier，立即返回 {@link java.util.Optional#empty()}； ROOT /
 *       RUNTIME_CONFIG / LABEL / BRANCH_SUMMARY / SYSTEM 消息只是向前跳过（不产生 debt），但 一旦 debt 落定，debt 之后（含
 *       head 与配置 / 标签 / 自定义消息）不再进入 request 前缀；
 *   <li>在 debt 前缀内寻找最近的 {@link
 *       fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot}， 缺失则拒绝；
 *   <li>按现有 compaction retained-range 语义构造消息；
 *   <li>leading SYSTEM message = {@code AgentSnapshot.systemPrompt} + canonical skills XML；
 *   <li>frozen tool definitions 按 snapshot canonical 顺序；
 *   <li>经 {@link fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer} 派生最终 cache
 *       control。
 * </ul>
 *
 * <p>约束：不读取 Store / Spring / live Definition / Clock；不写 Entry / head；纯函数（除可注入 {@code
 * PromptCacheAffinityKeyFactory}）。
 */
package fun.fengwk.kkstudio.harness.runtime.model.plan;
