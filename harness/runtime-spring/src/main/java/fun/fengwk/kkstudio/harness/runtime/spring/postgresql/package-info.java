/**
 * PostgreSQL adapters for the seven-table Harness Runtime durable protocol and lossy Work wake
 * hints.
 *
 * <p>The Store implements only {@link HarnessStore} primitives; LISTEN/NOTIFY only reduces dispatch
 * latency and periodic polling remains the recovery path. Agent Loop decisions and aggregate
 * transitions remain owned by the pure Java runtime.
 */
package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
