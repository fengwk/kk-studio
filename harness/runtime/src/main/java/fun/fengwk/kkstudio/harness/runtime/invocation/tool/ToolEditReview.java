package fun.fengwk.kkstudio.harness.runtime.invocation.tool;

import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;

import java.util.Objects;

/**
 * 审批前从目标 Daemon 实际文件生成的不可变审查事实。
 *
 * <p>完整 diff 用于用户审查；执行只从同一快照提取 expectedFile，不重新计算或接受客户端传回的审查正文。
 */
public record ToolEditReview(ExpectedFile expectedFile, String diff) {

  public ToolEditReview {
    Objects.requireNonNull(expectedFile, "expectedFile");
    Objects.requireNonNull(diff, "diff");
    if (diff.isEmpty()) {
      throw new IllegalArgumentException("editReview.diff must not be empty");
    }
  }
}
