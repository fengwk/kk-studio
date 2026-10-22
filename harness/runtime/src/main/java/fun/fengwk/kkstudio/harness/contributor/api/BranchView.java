package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.List;
import java.util.Optional;

/**
 * 分支视图纯接口：提供对所属 Contributor 的 branch custom state 的只读查询。
 *
 * <p>查询由 Platform 严格限定在当前 Contributor 和当前 Assistant branch 的 root-to-head 路径上， 与其它 Session、Thread 及
 * sibling branch 完全隔离。
 */
public interface BranchView {

  /** 返回匹配 customType 的所有状态快照列表（root-to-head 顺序）。 */
  List<CustomStateSnapshot> customEntries(String customType);

  /** 返回匹配 customType 的最新（head 最近）状态快照；没有匹配返回 empty。 */
  Optional<CustomStateSnapshot> latestCustomEntry(String customType);

  /** 返回当前 branch 生效 settings 中用户设定的 Goal；未设置或已被用户清除时返回 empty。 */
  Optional<GoalSnapshot> goal();
}
