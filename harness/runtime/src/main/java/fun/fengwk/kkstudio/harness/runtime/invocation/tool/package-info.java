/**
 * Durable current state of one Tool invocation and its frozen request facts.
 *
 * <p>The types in this package are plain durable value/state values persisted as the current state
 * of {@code harness_tool_invocation}; they are not events, not an Event Sourcing log and not
 * repository aggregates. The request freezes the ToolCall arguments and the actual Environment
 * route; approval decisions are part of the invocation state, not a separate approval table.
 * Scheduling lease and wake fencing are owned exclusively by the {@link
 * fun.fengwk.kkstudio.harness.runtime.work} package.
 */
package fun.fengwk.kkstudio.harness.runtime.invocation.tool;
