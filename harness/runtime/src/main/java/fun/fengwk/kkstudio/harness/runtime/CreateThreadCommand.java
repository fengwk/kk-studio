package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;

import java.util.Objects;

/**
 * 不可变的 create-thread 请求：新 Session ROOT 的初始完整 {@link BranchSettings} 与 Thread YOLO runtime policy。
 *
 * <p>{@code title} 为可选的用户可见标签，由 {@link fun.fengwk.kkstudio.harness.runtime.session.Session}
 * 校验。设计上没有 {@code createRequestId}：7 表模型没有 create 幂等键，因此 {@link HarnessRuntime#createThread} 是非幂等的。
 */
public record CreateThreadCommand(
    String title,
    BranchSettings branchSettings,
    boolean yoloEnabled,
    SubagentContext subagentContext) {

  /** 构造普通用户 Thread。 */
  public CreateThreadCommand(String title, BranchSettings branchSettings, boolean yoloEnabled) {
    this(title, branchSettings, yoloEnabled, null);
  }

  public CreateThreadCommand {
    branchSettings = Objects.requireNonNull(branchSettings, "branchSettings");
  }
}
