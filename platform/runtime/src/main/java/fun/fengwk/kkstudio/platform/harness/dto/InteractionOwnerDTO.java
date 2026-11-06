package fun.fengwk.kkstudio.platform.harness.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 服务端解析的产品归属或独立 Agent 任务。 */
@Data
public class InteractionOwnerDTO {

  /** 产品归属类型；{@code AGENT_TASK} 表示没有产品页面的独立执行根。 */
  private String kind;

  /** 归属 id：产品 owner 的 id 或独立执行根的 Thread id。 */
  private String id;

  /** 归属展示标题；没有时省略。 */
  private @Nullable String title;

  /** 产品页面链接；没有产品页面时省略。 */
  private @Nullable String link;
}
