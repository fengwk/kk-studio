package fun.fengwk.kkstudio.harness.api.tool;

import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;

import java.util.Objects;

/**
 * 审批前从目标 Daemon 实际文件生成的不可变审查事实。
 *
 * <p>完整 diff 用于用户审查；执行只从同一快照提取 expectedFile，不重新计算或接受客户端传回的审查正文。
 *
 * <p>{@link LossyDecode} 只在原文件以声明编码解码时替换过非法字节序列、且最终文本仍含 U+FFFD 时存在，向审批者披露保存后这些位置会写成 U+FFFD。
 */
public record ToolEditReview(ExpectedFile expectedFile, String diff, LossyDecode lossyDecode) {

  /** 原文件解码时发生有损替换的事实：显示名与实际替换的字节序列数量。 */
  public record LossyDecode(String encoding, int invalidSequences) {

    public LossyDecode {
      Objects.requireNonNull(encoding, "lossyDecode.encoding");
      if (encoding.isBlank()) {
        throw new IllegalArgumentException("lossyDecode.encoding must not be blank");
      }
      if (invalidSequences <= 0) {
        throw new IllegalArgumentException("lossyDecode.invalidSequences must be positive");
      }
    }
  }

  public ToolEditReview {
    Objects.requireNonNull(expectedFile, "expectedFile");
    Objects.requireNonNull(diff, "diff");
    if (diff.isEmpty()) {
      throw new IllegalArgumentException("editReview.diff must not be empty");
    }
  }
}
