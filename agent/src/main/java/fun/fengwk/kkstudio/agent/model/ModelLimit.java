package fun.fengwk.kkstudio.agent.model;

import lombok.Builder;
import lombok.Data;

/**
 * ModelLimit 表示模型的输入输出限制。
 *
 * @author fengwk
 */
@Builder
@Data
public class ModelLimit {

  /** 上下文窗口大小。 */
  private final Integer context;

  /** 最大输入长度。 */
  private final Integer input;

  /** 最大输出长度。 */
  private final Integer output;
}
