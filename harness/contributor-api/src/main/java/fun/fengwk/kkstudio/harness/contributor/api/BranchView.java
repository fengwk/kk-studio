package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.List;
import java.util.Optional;

/** 分支视图纯接口：提供对所属 contributor 的 branch custom state 的只读查询。 */
public interface BranchView {

  /** 返回匹配 customType 的所有状态快照列表（root-to-head 顺序）。 */
  List<CustomStateSnapshot> customEntries(String customType);

  /** 返回匹配 customType 的最新（head 最近）状态快照；没有匹配返回 empty。 */
  Optional<CustomStateSnapshot> latestCustomEntry(String customType);
}
