package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.SubagentContext;

import java.util.Objects;

/**
 * 不可变的 create-thread 请求：新 Session ROOT 的初始完整 {@link BranchSettings} 与 Thread YOLO runtime policy。
 *
 * <p>设计上没有 {@code createRequestId}：7 表模型没有 create 幂等键，因此 {@link HarnessRuntime#createThread} 是非幂等的。
 */
public record CreateThreadCommand(
    BranchSettings branchSettings, boolean yoloEnabled, SubagentContext subagentContext) {

  /** 构造普通用户 Thread。 */
  public CreateThreadCommand(BranchSettings branchSettings, boolean yoloEnabled) {
    this(branchSettings, yoloEnabled, null);
  }

  public CreateThreadCommand {
    branchSettings = Objects.requireNonNull(branchSettings, "branchSettings");
  }
}
