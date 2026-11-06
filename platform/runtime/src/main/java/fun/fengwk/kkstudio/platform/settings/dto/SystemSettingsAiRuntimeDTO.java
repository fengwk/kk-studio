package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;

import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;

import java.util.List;

/**
 * aiRuntime section：共享调用重试、自动压缩与 subagent 预算。
 *
 * <p>{@code subagentMaxTotalConcurrency} 为 0 表示不额外限制（无 cap）。{@code modelHttpRetryStatusCodes} 为
 * 400–599 的 HTTP error 状态白名单：命中允许进入重试预算，未列出直接失败。
 */
@Data
public class SystemSettingsAiRuntimeDTO {

  private Integer retryMaxRetries;

  private InvocationRetryBackoffStrategy retryBackoffStrategy;

  private Long retryBaseDelayMillis;

  private Long retryMaxDelayMillis;

  private Integer compactionKeepRecentTokens;

  private Integer subagentMaxDepth;

  private Integer subagentMaxConcurrency;

  private Integer subagentMaxTotalConcurrency;

  private Integer subagentMaxTurns;

  private List<Integer> modelHttpRetryStatusCodes;
}
