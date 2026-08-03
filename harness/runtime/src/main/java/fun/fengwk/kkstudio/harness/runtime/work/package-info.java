/**
 * The single Work mailbox for THREAD / MODEL / TOOL scheduling.
 *
 * <p>{@link Work} is the durable current scheduling state of one target and the exclusive owner of
 * scheduling lease and wake fencing: {@code wake_version} protects against lost wakes, {@code
 * lease_token}/{@code lease_until} fence stale workers. It is not an event log, not a job queue and
 * not a repository aggregate; it stores no target business state, attempt, result or approval.
 */
package fun.fengwk.kkstudio.harness.runtime.work;
