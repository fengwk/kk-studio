package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

/** Agent 模型配置携带的 Token 限制；{@code output} 必须满足 {@code <= context}。 */
@Data
public class AgentModelLimitDTO {

  /**
   * 模型上下文窗口大小，单位为 Token。使用 {@code Integer}，使 wire format 始终为普通 JSON 数字， 不受 convention4j 将 {@code
   * Long} 自动配置为字符串的影响。
   */
  private Integer context;

  /** 每轮最大输出 Token 数；必须满足 {@code > 0} 且 {@code <= context}。 */
  private Integer output;
}
