package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** 每天重复的价格时段：{@code timeZone} 本地时间 {@code [start, end)}，{@code end} 早于 {@code start} 表示跨越午夜。 */
@Data
public class AgentModelPricingWindowDTO {

  /** 必填 IANA 时区，如 {@code Asia/Shanghai}。 */
  private String timeZone;

  /** 必填开始时间，{@code HH:mm}。 */
  private String start;

  /** 必填结束时间（不含），{@code HH:mm}，与开始时间不同。 */
  private String end;
}
