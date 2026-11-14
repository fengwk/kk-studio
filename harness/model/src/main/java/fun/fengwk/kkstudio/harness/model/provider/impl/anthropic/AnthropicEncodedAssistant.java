package fun.fengwk.kkstudio.harness.model.provider.impl.anthropic;

import tools.jackson.databind.node.ObjectNode;

/**
 * 编码完成的 assistant wire 消息；{@code resetsHistory} 表示该消息的 replay 代表已摘要历史，必须省略此前的 wire messages。
 *
 * @param node 已编码的 assistant 消息
 * @param resetsHistory 是否以 compaction 回放开头、从而整体省略此前的 wire messages
 */
record AnthropicEncodedAssistant(ObjectNode node, boolean resetsHistory) {}
