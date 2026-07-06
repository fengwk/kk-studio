package fun.fengwk.kkstudio.agent.provider;

import lombok.Builder;
import lombok.Data;

import fun.fengwk.kkstudio.agent.session.payload.AssistantMetadata;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;

import java.util.List;

/**
 * AssistantResponse 表示一次 assistant complete 回调给出的最终完整结果。
 *
 * @author fengwk
 */
@Builder
@Data
public class AssistantResponse {

  /** 最终完整文本。 */
  private final String text;

  /** 最终完整 thinking。 */
  private final String thinking;

  /** 最终完整工具调用列表。 */
  private final List<ToolCall> toolCalls;

  /** assistant 结束元信息。 */
  private final AssistantMetadata metadata;
}
