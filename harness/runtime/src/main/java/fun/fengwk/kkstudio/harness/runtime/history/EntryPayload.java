package fun.fengwk.kkstudio.harness.runtime.history;

/**
 * Entry Tree 节点负载抽象。
 *
 * <p>每种实现必须能唯一标识自身所属的 {@link EntryType}，以便在追加 Entry 时做强制校验。
 */
public sealed interface EntryPayload
    permits RootPayload,
        TurnStartPayload,
        MessagePayload,
        CustomMessagePayload,
        AssistantErrorPayload,
        AssistantAbortedPayload,
        TurnEndPayload {

  /** 返回该负载对应的 Entry 类型。 */
  EntryType type();
}
