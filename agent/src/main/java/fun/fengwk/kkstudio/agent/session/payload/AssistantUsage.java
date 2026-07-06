package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * assistant 用量信息。
 *
 * @author fengwk
 */
@Data
public class AssistantUsage {

  /** 输入 token 数。 */
  private Integer inputTokens;

  /** 输出 token 数。 */
  private Integer outputTokens;

  /** 总 token 数。 */
  private Integer totalTokens;

  /** 缓存读取 token 数。 */
  private Integer cacheReadTokens;

  /** 缓存写入 token 数。 */
  private Integer cacheWriteTokens;
}
