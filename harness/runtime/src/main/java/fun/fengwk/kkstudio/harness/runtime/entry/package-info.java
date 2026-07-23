/**
 * Session Tree 上最终 Entry payload family，直接实现 {@link
 * fun.fengwk.kkstudio.harness.kernel.session.EntryPayload}，自报 {@link
 * fun.fengwk.kkstudio.harness.kernel.session.EntryType}。
 *
 * <p>{@link RuntimeEntryPayload} 是 sealed 根类型，permits {@link RootEntryPayload} / {@link
 * MessageEntryPayload} / {@link CustomMessageEntryPayload} / {@link CompactionEntryPayload} /
 * {@link AssistantErrorEntryPayload} / {@link LabelEntryPayload} / {@link
 * BranchSummaryEntryPayload}，分别对应 ROOT / MESSAGE / CUSTOM_MESSAGE / COMPACTION / ASSISTANT_ERROR /
 * LABEL / BRANCH_SUMMARY。
 *
 * <p>payload 复用 {@link fun.fengwk.kkstudio.harness.runtime.session.AgentMessage} / {@link
 * fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata} / {@link
 * fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent} 等值对象；不变量：assistant metadata 与
 * ASSISTANT role 严格一致；tool-call 内容与 {@link
 * fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason#TOOL_CALLS} 严格一致；compaction
 * 数值非负非零；assistant error 携带 {@link fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError}
 * 最小快照（kind + message）。
 *
 * <p>{@link RuntimeEntryPayloadJsonCodec} 是这些 payload 与 {@link
 * fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot} 的严格 durable JSON 边界。
 */
package fun.fengwk.kkstudio.harness.runtime.entry;
