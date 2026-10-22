package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * 模型组配置：有序成员、命中后立即冻结/降级的 HTTP 状态码，以及固定冷却秒数。
 *
 * <p>成员顺序就是 Fill First 的选择顺序；空状态码集合合法（只按普通重试耗尽后的降级路径），冷却 0 表示只在本逻辑调用内继续下一项、不写跨调用冷却。
 */
@Data
public class AgentModelGroupDTO {

  /** 必填非空有序成员列表；禁止重复引用、禁止嵌套组。 */
  private List<AgentModelGroupMemberDTO> members;

  /** 必填 HTTP 400–599 状态码集合；空集合合法，元素不得重复。 */
  private List<Integer> fallbackStatusCodes;

  /** 必填非负整数秒；0 表示仅本次降级。 */
  private Integer cooldownSeconds;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown model group field: " + name);
  }
}
