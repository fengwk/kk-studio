package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.harness.environment.server.terminal.EnvironmentTerminalListener;
import fun.fengwk.kkstudio.harness.environment.server.terminal.ShellTopics;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDelivery;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;

import java.util.Objects;
import java.util.UUID;

/** 生产终端响应监听器：接收 Daemon 终端响应，封装为 {@link TerminalDelivery} 并通过通知总线路由投递至目标浏览器所在的 App 节点。 */
final class ShellEventPublisher implements EnvironmentTerminalListener {

  private final NotificationBus bus;

  ShellEventPublisher(NotificationBus bus) {
    this.bus = Objects.requireNonNull(bus, "bus");
  }

  @Override
  public void onTerminalResponse(
      UUID leaseToken, UUID daemonInstanceId, TerminalResponse response) {
    Objects.requireNonNull(leaseToken, "leaseToken");
    Objects.requireNonNull(daemonInstanceId, "daemonInstanceId");
    Objects.requireNonNull(response, "response");
    bus.publish(
        ShellTopics.EVENT,
        NotificationAddress.node(response.route().appNodeId()),
        new TerminalDelivery(bus.nodeId(), leaseToken, daemonInstanceId, response));
  }
}
