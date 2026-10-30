package fun.fengwk.kkstudio.harness.runtime.store;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntrySlice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Entry 聚合的事务内视图。
 *
 * <p>Entry 是 append-only 不可变历史：写入前由存储实现校验完整路径；读取保持 root-to-head 或确定性时间顺序。删除仅供 Session 级深删除使用。
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
   * 读取 head 相对游标的增量链。
   *
   * <p>{@code afterEntryId} 可为 null。命中当前祖先时 {@code baseEntryId} 等于游标且不含游标本身，游标即 head
   * 时列表为空。游标不是该祖先、属于另一 Session 或未知时返回完整链且 {@code baseEntryId} 为 null。head 缺失沿 {@link
   * #loadPath(UUID)} 抛 {@link IllegalArgumentException}。
   */
  EntrySlice loadSlice(UUID headEntryId, UUID afterEntryId);

  /** 读取祖先链上指定 contributor 的 Custom Entry，保持 root-to-head 顺序。 */
  List<Entry> loadContributorCustom(UUID headEntryId, String contributorId);

  /** 读取指定 branch 的生效 settings 快照。 */
  BranchSettings loadBranchSettings(UUID headEntryId);

  /** 读取 Session 全部 Entry，按 {@code (createdAt, unsigned UUID)} 升序。 */
  List<Entry> loadBySession(UUID sessionId);

  /** 按原 ToolInvocation id 反查已物化的 ToolResult MESSAGE Entry。 */
  Optional<Entry> findToolResultByInvocationId(UUID sessionId, UUID invocationId);

  /** 删除 Session 全部 Entry 并返回行数；以叶子优先顺序删除。 */
  int delete(UUID sessionId);
}
