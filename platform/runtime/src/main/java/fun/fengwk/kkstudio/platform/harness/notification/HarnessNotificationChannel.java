package fun.fengwk.kkstudio.platform.harness.notification;

/**
 * Harness 事务内失效提示共享的 PostgreSQL LISTEN/NOTIFY channel 协议。
 *
 * <p>channel 名只在此定义一次：写入口（Harness 存储事务）用它投递，公开订阅源（本包）用它监听，web 组合根只按 {@link
 * HarnessNotificationBinding} 装配监听循环。提示是有损失效信号，权威事实始终回读数据库。
 *
 * @see HarnessChangeNotifications
 */
public final class HarnessNotificationChannel {

  /** Thread version 失效：payload 为 {@code {threadId}:{version}}。 */
  public static final String THREAD_VERSION = "harness_thread_version";

  /** 执行树失效：payload 为真实执行根 Thread id。 */
  public static final String THREAD_TREE = "harness_thread_tree";

  /** 待处理交互失效：payload 为真实执行根 Thread id，来源事实缺失时为空串触发全量 resync。 */
  public static final String TOOL_INTERACTION = "harness_tool_interaction";

  private HarnessNotificationChannel() {}
}
