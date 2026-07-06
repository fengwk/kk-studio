package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import lombok.Data;

/**
 * assistant 结束元信息。
 *
 * @author fengwk
 */
@Data
public class AssistantMetadata {

  /** provider 返回的响应标识。 */
  private String id;

  /** 实际执行的模型名。 */
  private String modelName;

  /** assistant 结束原因。 */
  private String finishReason;

  /** assistant 调用的用量统计。 */
  private AssistantUsage usage;
}
