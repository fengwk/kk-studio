package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/** 单个 Entry branch 的设置快照。 */
@Data
public class HarnessBranchSettingsDTO {

  /** 必填 Agent definition 名（canonical text，≤128 字符）。 */
  private String agentName;

  /** 必填 provider/model/variant 选择（三项均非空白）。 */
  private HarnessModelSelectionDTO model;

  /**
   * Environment 引用：nullable canonical name（非 blank、无首尾空白、不含 {@code '/'}、≤64 字符）。
   *
   * <p>null 表示该 branch 未选择 Environment。HTTP 全局配置默认省略 null，但完整快照语义要求该字段总是显式出现，因此这里强制 {@link
   * JsonInclude.Include#ALWAYS}。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String environmentName;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private boolean environmentNameFieldPresent;

  @JsonSetter("environmentName")
  public void setEnvironmentName(Object value) {
    this.environmentName =
        HarnessRuntimeDtoSupport.requireJsonString(value, "branchSettings.environmentName");
    this.environmentNameFieldPresent = true;
  }

  @JsonIgnore
  public boolean hasEnvironmentNameField() {
    return environmentNameFieldPresent;
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown branch settings field: " + name);
  }
}
