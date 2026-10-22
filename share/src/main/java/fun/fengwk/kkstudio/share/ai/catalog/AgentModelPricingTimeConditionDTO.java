package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.util.List;

/** IANA 时区中的每日左闭右开时间窗；weekdays 使用 ISO 1=周一...7=周日。全天 wire 为 00:00-24:00。 */
@Data
public class AgentModelPricingTimeConditionDTO {

  private String start;

  private String end;

  private String timezone;

  private List<Integer> weekdays;
}
