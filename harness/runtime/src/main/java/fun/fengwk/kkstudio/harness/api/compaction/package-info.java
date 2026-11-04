/**
 * 对话压缩的公开值类型：部署配置、执行阶段、触发原因、切分准备与 COMPACTION turn 的冻结元数据。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig} 只携带 {@code
 * keepRecentTokens}，并把 keep / reserve / threshold / manual / output 的换算集中为纯函数；{@link
 * fun.fengwk.kkstudio.harness.api.compaction.CompactionPhase} 与 {@link
 * fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger} 是一次压缩调用的阶段与 durable 原因。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation} 是一次压缩的切分事实与待摘要消息；
 * {@link fun.fengwk.kkstudio.harness.api.compaction.CompactionStart} 是挂在 TURN_START 上的最小 durable
 * 元数据， 用于把压缩调用与普通调用区分开。规划器、prompt 模板、结果评估与摘要装配都留在 runtime 侧，依赖本包但不反向泄漏实现。
 */
package fun.fengwk.kkstudio.harness.api.compaction;
