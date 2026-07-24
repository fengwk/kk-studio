/**
 * final PostgreSQL snapshot-first read model。
 *
 * <p>生产查询只读 {@code harness_session}/{@code harness_entry}/{@code harness_thread}/ {@code
 * harness_thread_input}/{@code harness_model_invocation}/{@code harness_tool_invocation}/{@code
 * harness_interaction}；Thread 展示状态从 durable facts 派生，不读 {@code harness_thread_event}。
 */
package fun.fengwk.kkstudio.core.harness.query;
