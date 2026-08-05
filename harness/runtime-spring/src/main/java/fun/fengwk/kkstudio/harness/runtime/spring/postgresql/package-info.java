/**
 * PostgreSQL persistence adapter for the seven-table Harness Runtime durable protocol.
 *
 * <p>The adapter implements only {@link HarnessStore} primitives. Agent Loop decisions and
 * aggregate transitions remain owned by the pure Java runtime.
 */
package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
