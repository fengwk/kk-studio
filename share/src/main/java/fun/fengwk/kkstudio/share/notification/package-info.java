/**
 * Transport-free notification contracts. Domains own fixed topics and strict immutable payload
 * codecs; the App composition root binds topics explicitly. Publications are best-effort
 * hints/events, not durable facts, authorization or exactly-once delivery.
 */
package fun.fengwk.kkstudio.share.notification;
