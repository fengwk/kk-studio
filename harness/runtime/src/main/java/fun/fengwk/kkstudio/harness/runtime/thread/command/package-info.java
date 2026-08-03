/**
 * Pure Thread command mailbox protocol and deterministic harvest reducer.
 *
 * <p>This package owns typed command values, enqueue batch CAS facts, derived command state and
 * branch/Thread policy reduction. Environment commands carry the canonical {@link
 * fun.fengwk.kkstudio.harness.tool.EnvironmentId} route identity; display names are not part of the
 * durable protocol. It does not persist commands or execute a second Thread loop.
 */
package fun.fengwk.kkstudio.harness.runtime.thread.command;
