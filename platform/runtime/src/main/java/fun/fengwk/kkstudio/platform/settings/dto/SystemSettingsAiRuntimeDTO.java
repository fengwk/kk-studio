package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO;

import java.util.List;

/**
 * aiRuntime section：共享调用重试、上下文压缩与 subagent 预算。
 *
 * <p>{@code subagentMaxTotalConcurrency} 为 0 表示不额外限制（无 cap）。{@code modelHttpRetryStatusCodes} 为
 * 400–599 的 HTTP error 状态白名单：命中允许进入重试预算，未列出直接失败。{@code compactionModel} 是可空的压缩模型引用 （模型或模型组加可选变体，与
 * Agent 执行选择同形状）；null 表示未配置，运行时改用对话当前模型。
 */
@Data
public class SystemSettingsAiRuntimeDTO {

  private Integer retryMaxRetries;

  private InvocationRetryBackoffStrategy retryBackoffStrategy;

  private Long retryBaseDelayMillis;

  private Long retryMaxDelayMillis;

  /** 可空压缩模型引用：模型或模型组加可选变体；null 表示使用对话当前模型。 */
  private @Nullable HarnessModelSelectionDTO compactionModel;

  private Integer compactionKeepRecentTokens;

  private Integer subagentMaxDepth;

  private Integer subagentMaxConcurrency;

  private Integer subagentMaxTotalConcurrency;

  private Integer subagentMaxTurns;

  private List<Integer> modelHttpRetryStatusCodes;
}
