/**
 * Redis Streams 等有界实时投影的 Runtime event model。
 *
 * <p>本包不保存 durable state，不承担重试、恢复、审计或 transcript materialization。客户端 cursor 失效时必须回到 PostgreSQL
 * snapshot，而不是依赖本包事件重建业务事实。
 */
package fun.fengwk.kkstudio.harness.runtime.realtime;
