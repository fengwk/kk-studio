/**
 * The sole notification runtime. The router owns publication phases, the inbox owns independent
 * ordered subscriber execution, and PG transport owns cross-node SQL, framing and reconnect
 * recovery. A recovery marker has its own control slot and cannot be blocked by a full normal
 * mailbox.
 */
package fun.fengwk.kkstudio.notification;
