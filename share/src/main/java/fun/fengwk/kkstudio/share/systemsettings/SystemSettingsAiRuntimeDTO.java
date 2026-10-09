package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;

import java.util.List;

/**
 * aiRuntime section：共享调用重试、自动压缩与 subagent 预算。
 *
 * <p>{@code retryBackoffStrategy} 取值仅为 {@code FIXED}/{@code EXPONENTIAL}；{@code
 * subagentMaxTotalConcurrency} 为 0 表示不额外限制（无 cap）。{@code modelHttpRetryStatusCodes} 为 400–599 的
 * HTTP error 状态白名单：命中允许进入重试预算，未列出直接失败。
 */
@Data
public class SystemSettingsAiRuntimeDTO {

  private Integer retryMaxRetries;

  private String retryBackoffStrategy;

  private Long retryBaseDelayMillis;

  private Long retryMaxDelayMillis;

  private Integer compactionKeepRecentTokens;

  private HarnessModelSelectionDTO compactionFallbackModel;

  private Integer subagentMaxDepth;

  private Integer subagentMaxConcurrency;

  private Integer subagentMaxTotalConcurrency;

  private Integer subagentMaxTurns;

  private List<Integer> modelHttpRetryStatusCodes;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings aiRuntime field: " + name);
  }
}
