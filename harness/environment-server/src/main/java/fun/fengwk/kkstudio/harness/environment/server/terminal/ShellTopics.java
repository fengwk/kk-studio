package fun.fengwk.kkstudio.harness.environment.server.terminal;

import fun.fengwk.kkstudio.share.notification.NotificationTopic;

/**
 * 人工 shell 的两个固定通知 topic 名称与其严格 codec 的唯一绑定。
 *
 * <p>发布侧（ShellGateway / Web 组合根的终端响应监听器）与订阅侧必须使用这里的同一实例；两者都不是 hint，shell 控制/事件绝不按实体合并。topic 名称固定为
 * {@code shell.command} 与 {@code shell.event}，不做动态主题或第二载体。
 */
public final class ShellTopics {

  /** 定向到 owner 的 shell 控制请求；根字段恰为 {@code {leaseToken,request}}。 */
  public static final NotificationTopic<TerminalDispatch> COMMAND =
      new NotificationTopic<>("shell.command", new TerminalDispatchCodec(), false);

  /** owner 回传的 shell 事件；根字段恰为 {@code {ownerNodeId,leaseToken,daemonInstanceId,response}}。 */
  public static final NotificationTopic<TerminalDelivery> EVENT =
      new NotificationTopic<>("shell.event", new TerminalDeliveryCodec(), false);

  private ShellTopics() {}
}
