package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Data;

/**
 * Model Request Debug 的请求预览入参：以 UI 当前草稿选择为输入，在真实 Thread snapshot 上做一次只读规划。
 *
 * <p>{@code model} 必填且必须是完整 canonical provider/model/variant 选择；{@code environmentName} 为可空
 * canonical Environment 名，null 明确表示本次预览未选择 Environment，缺省与显式 null 同义。字段只影响这次现算预览的工具/技能/系统指令与
 * cache，不改变任何持久事实。
 */
@Data
public class HarnessModelRequestDebugRequestDTO {

  /** 必填的草稿 provider/model/variant 选择。 */
  private HarnessModelSelectionDTO model;

  /** 草稿 Environment 名；null 表示未选择 Environment。 */
  private String environmentName;

  @JsonSetter("environmentName")
  public void setEnvironmentName(Object value) {
    this.environmentName = HarnessRuntimeDtoSupport.requireJsonString(value, "environmentName");
  }

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown model request debug field: " + name);
  }
}
