/**
 * 最终可保留的 Session Entry Tree 领域协议。
 *
 * <p>Entry 是不可变 Session 历史节点：ROOT 保存初始 branch settings，TURN_START 保存一次 Model response turn 的完整
 * settings 快照，MESSAGE / CUSTOM_MESSAGE / ASSISTANT_ERROR / ASSISTANT_ABORTED 保存对话事实，
 * MODEL_ATTEMPT_FAILURE 保存 provider-transparent 的失败 attempt 审计，CUSTOM 保存业务插件追加的透明 branch state（不参与
 * turn grammar、默认不投影），TURN_END 保存关闭结果与 continuation obligation。EntryPath 校验 root-to-head 链的同
 * Session、parent 连续、turn open/close 与 createdAt 不变量； baseSettings 沿 path 推导最近的 ROOT/TURN_START 完整
 * snapshot。类型引用优先复用已存在的 {@link fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason}、{@link
 * fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome}、{@link
 * fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings} 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection}，避免同义重复。
 */
package fun.fengwk.kkstudio.harness.runtime.history;
