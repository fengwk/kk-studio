/**
 * Spring/PostgreSQL/Redis infrastructure adapters for the pure Java Harness Runtime.
 *
 * <p>This module implements {@link HarnessStore} and process wiring without owning Thread next-step
 * selection, Turn protocol, retry, Tool sibling aggregation or any other Agent Loop business rule.
 * Technology dependencies are introduced only by the concrete adapter slice that directly uses
 * them.
 */
package fun.fengwk.kkstudio.harness.runtime.spring;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
