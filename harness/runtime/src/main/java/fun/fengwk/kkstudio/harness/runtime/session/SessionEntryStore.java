package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Optional;

/** Session Entry 查询端口。loadPath 只能读取指定 leaf 的祖先链。 */
public interface SessionEntryStore {
  Optional<SessionEntry> find(String sessionId, String entryId);

  /** 返回从根到 leaf 的链，并拒绝断链、循环和跨 Session 父引用。 */
  List<SessionEntry> loadPath(String sessionId, String leafEntryId);
}
