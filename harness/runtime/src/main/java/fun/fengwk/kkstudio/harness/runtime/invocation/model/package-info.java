/**
 * Durable current state of one Model invocation and its frozen request facts.
 *
 * <p>The types in this package are plain durable value/state values persisted as the current state
 * of {@code harness_model_invocation}; they are not events, not an Event Sourcing log and not
 * repository aggregates. The request freezes the resolved provider request, tool/skill bindings,
 * actual Environment route and Thread YOLO policy; retry replays the original request only.
 * Scheduling lease and wake fencing are owned exclusively by the {@link
 * fun.fengwk.kkstudio.harness.runtime.work} package.
 */
package fun.fengwk.kkstudio.harness.runtime.invocation.model;
