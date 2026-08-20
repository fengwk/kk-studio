package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;

/**
 * aiRuntime section：共享调用重试、自动压缩与 subagent 预算。
 *
 * <p>{@code retryBackoffStrategy} 取值仅为 {@code FIXED}/{@code EXPONENTIAL}；{@code
 * subagentMaxTotalConcurrency} 为 null 表示不额外限制（无 cap），{@code subagentIdleTimeoutMillis} 为 0 表示关闭。
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

  private Long subagentIdleTimeoutMillis;

  private Integer subagentMaxTurns;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings aiRuntime field: " + name);
  }
}
