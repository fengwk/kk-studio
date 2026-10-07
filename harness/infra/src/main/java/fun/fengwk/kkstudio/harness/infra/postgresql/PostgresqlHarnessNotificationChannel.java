package fun.fengwk.kkstudio.harness.infra.postgresql;

/**
 * Harness 事务内失效提示共享的 PostgreSQL LISTEN/NOTIFY channel。
 *
 * <p>这些 channel 名是写入方（本模块）与监听方（web 事件 hub）之间的协议常量，必须与 {@code ThreadVersionHub}、 {@code
 * ExecutionTreeChangeHub}、{@code InteractionChangeHub} 的 LISTEN channel 完全一致。提示是有损失效信号，权威事实始终回读数据库。
 */
final class PostgresqlHarnessNotificationChannel {

  /** Thread version 失效：payload 为 {@code {threadId}:{version}}。 */
  static final String THREAD_VERSION = "harness_thread_version";

  /** 执行树失效：payload 为真实执行根 Thread id。 */
  static final String THREAD_TREE = "harness_thread_tree";

  /** 待处理交互失效：payload 为真实执行根 Thread id，来源事实缺失时为空串触发全量 resync。 */
  static final String TOOL_INTERACTION = "harness_tool_interaction";

  private PostgresqlHarnessNotificationChannel() {}
}
