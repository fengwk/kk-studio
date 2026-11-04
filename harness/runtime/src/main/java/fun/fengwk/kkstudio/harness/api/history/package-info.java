/**
 * Session Entry Tree 的公开值类型：节点、sealed payload 家族、路径视图与回合边界枚举。
 *
 * <p>{@link Entry} 是不可变的追加节点；{@link EntryPayload} 收拢全部 payload 形状。
 *
 * <p>{@link EntryPath} 校验 root-to-head 链的不变量，{@link EntrySlice} 只表达其上的增量视图；{@link ScopedBranchView}
 * 把同一条链收窄为单个 Contributor 可见的 {@link
 * fun.fengwk.kkstudio.harness.contributor.api.BranchView}，执行期与规划期共用这一实现。
 *
 * <p>branch settings 与 turn 边界语义取自 {@code api.entry}，消息与内容取值取自 {@code api.session}。
 *
 * <p>Entry payload 编解码与纯 payload mapper 位于 {@code harness.codec}，history normalization 留在 {@code
 * harness.runtime.history}；两侧共用 {@link CanonicalIdentifiers} 与 {@link JsonObjects} 的 canonical 校验。
 */
package fun.fengwk.kkstudio.harness.api.history;
