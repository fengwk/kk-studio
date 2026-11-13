/**
 * 对话压缩的公开值类型：部署配置、触发原因、切分点与 COMPACTION turn 的冻结元数据。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig} defines keepRecent and the
 * summary output budget. {@link fun.fengwk.kkstudio.harness.api.compaction.CompactionTrigger}
 * records why a compaction was requested. {@link
 * fun.fengwk.kkstudio.harness.api.compaction.CompactionPreparation} identifies the cut point, while
 * {@link fun.fengwk.kkstudio.harness.api.compaction.CompactionStart} stores the frozen metadata on
 * the owner's TURN_START. Planning and result evaluation remain in the runtime implementation.
 */
package fun.fengwk.kkstudio.harness.api.compaction;
