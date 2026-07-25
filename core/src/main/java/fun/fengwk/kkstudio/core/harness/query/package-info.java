/**
 * final PostgreSQL snapshot-first read model。
 *
 * <p>生产查询只读 {@code harness_session}/{@code harness_entry}/{@code harness_thread}/ {@code
 * harness_thread_input}/{@code harness_model_invocation}/{@code harness_tool_invocation}/{@code
 * harness_interaction}；Thread 展示状态由 {@link DerivedThreadStatus} 从 durable facts 派生为 {@code RUNNING
 * > WAITING > RUNNABLE > IDLE}，不读任何 durable event journal，也不发出 Thread 级 FAILED/RETRYING。
 */
package fun.fengwk.kkstudio.core.harness.query;
