package fun.fengwk.kkstudio.harness.spi.store;

import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPathMetrics;
import fun.fengwk.kkstudio.harness.api.history.EntrySlice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry 聚合的事务内视图。
 *
 * <p>Entry 是 append-only 不可变历史：写入前由存储实现校验完整路径；读取保持 root-to-head 或存储追加顺序（创建顺序）。删除仅供 Session 级深删除使用。
 */
public interface EntryStore {

  /** 追加一个不可变 Entry；非法序列抛 {@link IllegalArgumentException}。 */
  void insert(Entry entry);

  /** 按 id 读取 Entry；不存在返回 {@link Optional#empty()}。 */
  Optional<Entry> find(UUID id);

  /** 按 sessionId 读取唯一 ROOT Entry；不存在返回 {@link Optional#empty()}。 */
  Optional<Entry> findRoot(UUID sessionId);

  /** 从 head Entry 向上回溯并返回 root-to-head 的不可变路径；head 不存在抛 {@link IllegalArgumentException}。 */
  EntryPath loadPath(UUID headEntryId);

  /**
   * 读取分类所需的当前 Entry 状态。
   *
   * <p>只回溯到 head、非控制锚点、open 或已关闭 Turn 引用、以及 settings 所需锚点后停止，不先调用 {@link #loadPath(UUID)}。head 缺失沿
   * {@link #loadPath(UUID)} 拒绝。
   */
  EntryCurrentState loadCurrent(UUID headEntryId);

  /**
   * 读取 root-to-head 的精确轮次与工具调用计数。
   *
   * <p>可以遍历祖先，但不得构造 {@link EntryPath}。head 缺失沿 {@link #loadPath(UUID)} 拒绝。
   */
  EntryPathMetrics loadPathMetrics(UUID headEntryId);

  /**
   * 判断 candidate 是否位于 head 的祖先链上（含 head 自身）。
   *
   * <p>找到目标后停止。未知 candidate 或其它分支返回 false；head 缺失沿 {@link #loadPath(UUID)} 拒绝。
   */
  boolean isAncestor(UUID headEntryId, UUID candidateEntryId);

  /**
   * 读取 head 相对游标的增量链。
   *
   * <p>{@code afterEntryId} 可为 null。命中当前祖先时 {@code baseEntryId} 等于游标且不含游标本身，游标即 head
   * 时列表为空。游标不是该祖先、属于另一 Session 或未知时返回完整链且 {@code baseEntryId} 为 null。head 缺失沿 {@link
   * #loadPath(UUID)} 抛 {@link IllegalArgumentException}。
   */
  EntrySlice loadSlice(UUID headEntryId, UUID afterEntryId);

  /** 读取指定 branch 的生效 settings 快照。 */
  BranchSettings loadBranchSettings(UUID headEntryId);

  /** 读取 Session 全部 Entry，按存储追加顺序（创建顺序）升序；同一 Session 内父节点必先于子节点。 */
  List<Entry> loadBySession(UUID sessionId);

  /**
   * 读取 Session 中游标之后的一页 Entry，按存储追加顺序（创建顺序）升序，至多 {@code limit} 条。
   *
   * <p>{@code afterEntryId} 为 null 时从最早一条开始；非 null 时必须属于同一 Session，否则拒绝。因为写入先校验完整父链， 同一 Session
   * 内父节点的追加序号必小于子节点，跨页读取不会让子节点先于父节点出现。
   */
  List<Entry> loadPageBySession(UUID sessionId, UUID afterEntryId, int limit);

  /** 按原 ToolInvocation id 反查已物化的 ToolResult MESSAGE Entry。 */
  Optional<Entry> findToolResultByInvocationId(UUID sessionId, UUID invocationId);

  /** 删除 Session 全部 Entry 并返回行数；以叶子优先顺序删除。 */
  int delete(UUID sessionId);
}
