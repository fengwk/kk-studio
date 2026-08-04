/**
 * Strict deterministic JSON protocol for durable Model/Tool Invocation value columns.
 *
 * <p>Each public codec owns one concrete Runtime value and delegates nested Provider/Tool
 * descriptors to their existing canonical codecs. Unknown, missing, duplicate, trailing or
 * wrong-type wire data is rejected before the domain constructor revalidates cross-field
 * invariants.
 */
package fun.fengwk.kkstudio.harness.runtime.invocation.codec;
