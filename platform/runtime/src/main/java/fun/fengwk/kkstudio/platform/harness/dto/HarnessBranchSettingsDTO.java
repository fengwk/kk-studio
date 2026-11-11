package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;

/**
 * 单个 Entry branch 的设置快照。
 *
 * <p>wire 顺序显式冻结为声明顺序，完整快照要求可空字段总是显式出现。
 */
@Data
@JsonPropertyOrder({"agentName", "execution", "environmentName"})
public class HarnessBranchSettingsDTO {

  /** 必填 Agent definition 名（canonical text，≤128 字符）。 */
  private String agentName;

  /** 必填执行选择：driver 名 + driver-specific 设置。 */
  private HarnessExecutionSelectionDTO execution;

  /**
   * Environment 引用：nullable canonical name（非 blank、无首尾空白、不含 {@code '/'}、≤64 字符）。
   *
   * <p>null 表示该 branch 未选择 Environment。HTTP 全局配置默认省略 null，但完整快照语义要求该字段总是显式出现，因此这里强制 {@link
   * JsonInclude.Include#ALWAYS}。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String environmentName;
}
