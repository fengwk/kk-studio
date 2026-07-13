/**
 * Durable Run 状态机、Run Event Journal 端口与单 Turn Worker。
 *
 * <p>数据库是 Run/lease/retry/terminal 的唯一恢复事实源；本包不依赖 Spring、MyBatis、HTTP 或工具执行器。 完整
 * Assistant/Compaction 通过事务端口进入 Session Tree，流式 Delta 仅进入 Run Event。
 */
package fun.fengwk.kkstudio.harness.runtime.run;
