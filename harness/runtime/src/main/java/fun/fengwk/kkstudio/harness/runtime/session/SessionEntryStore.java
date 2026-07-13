package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Optional;

/** Session Entry 查询端口。所有查询都限定 Session，避免扫描 Run Event。 */
public interface SessionEntryStore {
  Optional<SessionEntry> find(long sessionId, long entryId);

  /** 返回从根到 leaf 的链，并拒绝断链、循环和跨 Session 父引用。 */
  List<SessionEntry> loadPath(long sessionId, long leafEntryId);

  /** 返回同一父节点下的子节点，供 branch/timeline 展示和 checkout 使用。 */
  List<SessionEntry> listChildren(long sessionId, Long parentEntryId);
}
