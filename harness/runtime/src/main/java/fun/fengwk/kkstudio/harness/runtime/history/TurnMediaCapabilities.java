package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.model.ModelInputModality;

import java.util.Set;

/**
 * Turn 内消费者可接受的内联媒体能力快照。
 *
 * <p>工具结果返回媒体时只能依据发起 Turn 已冻结的消费者能力判断，不得再反查 Provider 或 ModelInvocation。{@code inputModalities}
 * 是消费者自身声明的输入模态；{@code userModalities} / {@code toolResultModalities} 是实际协议允许的
 * 内容位置。二者同时满足才允许作为工具结果读取。
 */
public record TurnMediaCapabilities(
    Set<ModelInputModality> inputModalities,
    Set<ModelInputModality> userModalities,
    Set<ModelInputModality> toolResultModalities) {

  public static final TurnMediaCapabilities NONE =
      new TurnMediaCapabilities(Set.of(), Set.of(), Set.of());

  public TurnMediaCapabilities {
    inputModalities = Set.copyOf(inputModalities);
    userModalities = Set.copyOf(userModalities);
    toolResultModalities = Set.copyOf(toolResultModalities);
  }

  /** 消费者是否声明接受该模态。 */
  public boolean declares(ModelInputModality modality) {
    return inputModalities.contains(modality);
  }

  /** 协议是否接受该模态作为工具结果或普通用户内容。 */
  public boolean acceptsProtocolPosition(ModelInputModality modality) {
    return toolResultModalities.contains(modality) || userModalities.contains(modality);
  }

  /** 工具结果媒体是否可由当前已冻结消费者送达。 */
  public boolean supportsToolResult(ModelInputModality modality) {
    return declares(modality) && acceptsProtocolPosition(modality);
  }
}
