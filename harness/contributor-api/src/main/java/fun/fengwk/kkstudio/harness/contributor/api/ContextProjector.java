package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;

import java.util.List;

/**
 * 分支上下文投影器：把不可变 {@link BranchView} 投影为要注入 model 上下文的冻结消息。
 *
 * <p>投影器只读分支视图、返回纯值；它不得执行状态变更，也不得接触 HarnessStore。Harness 默认不把 CUSTOM Entry 投影给
 * provider，是否调用投影器由调用方按投影器的 scoped {@link ContributionId} 决定。
 */
public interface ContextProjector {

  /** 返回要注入 model 上下文的冻结消息；返回不可变列表。 */
  List<AgentMessage> project(BranchView view);
}
