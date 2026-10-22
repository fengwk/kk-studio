package fun.fengwk.kkstudio.harness.contributor.api;

import java.util.List;

/**
 * 分支上下文投影器：把不可变 {@link BranchView} 投影为要注入 model 上下文的文本片段。
 *
 * <p>投影器只读分支视图、返回纯值；它不得执行状态变更，也不得接触底层存储。
 */
public interface ContextProjector {

  /** 返回要注入 model 上下文的冻结文本片段；返回不可变列表。 */
  List<ContextFragment> project(BranchView view);
}
