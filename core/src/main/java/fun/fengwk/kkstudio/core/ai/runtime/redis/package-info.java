/**
 * Production Redis realtime projection adapter implementing {@link
 * fun.fengwk.kkstudio.harness.runtime.port.RealtimeEventSink}.
 *
 * <p>This package owns neither durable state, worker lifecycle nor Web SSE. Redis carries only a
 * lossy bounded realtime projection; PostgreSQL owns all Harness activation.
 */
package fun.fengwk.kkstudio.core.ai.runtime.redis;
