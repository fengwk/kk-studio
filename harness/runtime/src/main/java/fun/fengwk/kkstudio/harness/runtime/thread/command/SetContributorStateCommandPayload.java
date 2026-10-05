package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;

import java.util.Objects;

/**
 * 可信内部入口写入 Contributor branch state 的配置命令：{@link ThreadCommandType#SET_CONTRIBUTOR_STATE}。
 *
 * <p>它是配置，不单独唤醒模型；规划时把 {@code state} 追加为 CUSTOM Entry，供 Contributor 渲染上下文（例如 Issue run scope 冻结）。
 * 普通 HTTP 客户端不允许提交该类型。
 */
public record SetContributorStateCommandPayload(CustomEntryPayload state)
    implements ThreadCommandPayload {

  public SetContributorStateCommandPayload {
    state = Objects.requireNonNull(state, "state");
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_CONTRIBUTOR_STATE;
  }
}
