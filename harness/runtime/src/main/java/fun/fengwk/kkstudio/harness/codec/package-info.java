/**
 * api 值类型的 wire codec：严格、确定性的 JSON 形态与由此派生的 request hash。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.codec.AgentMessageJsonCodec} 定义 {@link
 * fun.fengwk.kkstudio.harness.api.session.AgentMessage} 与 durable 内容记录的 JSON 形态，同时提供 request
 * 形态（允许瞬时 attachment）。{@link fun.fengwk.kkstudio.harness.codec.ExecutionSelectionJsonCodec} 定义
 * branch settings 与 SET_EXECUTION command 共用的 {@link
 * fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection} 形态。{@link
 * fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec} 在两者之上定义 typed Thread command
 * payload 的 durable/request 形态，并以 canonical 信封计算 request hash。{@link
 * fun.fengwk.kkstudio.harness.codec.RealtimeEventJsonCodec} 定义 {@link
 * fun.fengwk.kkstudio.harness.api.realtime.RealtimeEvent} 的 live overlay 形态。
 *
 * <p>Entry payload 的 durable 形态由 {@link
 * fun.fengwk.kkstudio.harness.codec.HistoryEntryPayloadJsonCodec} 按 {@link
 * fun.fengwk.kkstudio.harness.api.history.EntryType} 分派，其 settings / model / assistant metadata
 * 子树与严格 JSON 原语由包内 {@link fun.fengwk.kkstudio.harness.codec.HistoryValueCodecs} 提供；{@link
 * fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper} 只把终态 ToolInvocation 与 history
 * normalization 事实纯映射为 Entry payload，不做持久化决策。
 *
 * <p>Tool 持久化列与 Daemon 预览共用的 binding、审批与编辑审查形态由 {@link
 * fun.fengwk.kkstudio.harness.codec.ToolBindingJsonCodec}、{@link
 * fun.fengwk.kkstudio.harness.codec.ToolApprovalJsonCodec} 与 {@link
 * fun.fengwk.kkstudio.harness.codec.ToolEditReviewJsonCodec} 定义，并共享 {@link
 * fun.fengwk.kkstudio.harness.codec.InvocationJsonSupport} 的严格 JSON 原语。
 *
 * <p>本包只做编解码、纯映射与 hash 推导，不含状态机、持久化、调度或 mailbox 归约；持久化命令、harvest 归约与执行状态机留在 {@code
 * fun.fengwk.kkstudio.harness.runtime}。
 */
package fun.fengwk.kkstudio.harness.codec;
