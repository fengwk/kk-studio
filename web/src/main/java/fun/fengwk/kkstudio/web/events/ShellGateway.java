package fun.fengwk.kkstudio.web.events;

import fun.fengwk.kkstudio.harness.environment.server.DaemonOfferResult;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.harness.environment.server.terminal.EnvironmentTerminalRoute;
import fun.fengwk.kkstudio.harness.environment.server.terminal.EnvironmentTerminalRouteSource;
import fun.fengwk.kkstudio.harness.environment.server.terminal.ShellTopics;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDelivery;
import fun.fengwk.kkstudio.harness.environment.server.terminal.TerminalDispatch;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorCode;
import fun.fengwk.kkstudio.harness.environment.terminal.ErrorDisposition;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalIdentity;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRequest;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalResponse;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute;
import fun.fengwk.kkstudio.share.notification.NotificationAddress;
import fun.fengwk.kkstudio.share.notification.NotificationBus;
import fun.fengwk.kkstudio.share.notification.NotificationSubscription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 浏览器与 shell owner 之间唯一的 app-events 后端网关。
 *
 * <p>它只经 {@link NotificationBus} 的 {@link ShellTopics#COMMAND}/{@link ShellTopics#EVENT} 两个固定 topic
 * 收发，绝不绕过 Bus 直连 Daemon：浏览器发来的 {@link TerminalCommand} 先经窄 {@link EnvironmentTerminalRouteSource}
 * 读取当前权威 READY owner/lease， 再作为 {@link TerminalDispatch} 投递到 owner 节点；owner 侧消费用同一个 {@link
 * EnvironmentDaemonServer#sendShell} 提交，若在真正递交前 CLOSED/BUSY 则在同一 {@code shell.event} topic
 * 回一个确定未执行的固定错误。
 *
 * <p><b>显示/操作围栏：</b>route 按真实 {@code connectionId + environment + viewer} 有界保存（每连接最多 {@value
 * #MAX_SCOPES_PER_CONNECTION} 个）。每个 scope 记录本次 OPEN/ATTACH 的 {@code requestId} 与代次：发起新 OPEN/ATTACH
 * 立即作废旧显示流，只有匹配本次请求的 ATTACHED 才建立绑定（ATTACH 还必须匹配声明 identity）。回执同时核对权威 owner/lease（{@code
 * delivery.ownerNodeId} 与 {@code delivery.leaseToken} 都必须等于缓存 route）与已绑定 daemon/terminal 身份；OP_ACK
 * 不按显示流过滤但同样受这些围栏约束。旧 owner/lease/daemon/terminal/stream 的回执一律丢弃；关闭或 Bus 重连会冻结缓存并递增代次，之后必须由浏览器显式重新
 * ATTACH 才建立新绑定——绝不自动重试副作用或 OPEN。
 *
 * <p><b>两种「确定未执行」：</b>最初查 READY route 失败（没有 READY 行或 PG 失败）由本连接直接回固定 NOT_EXECUTED 错误；命令一旦 publish
 * 成功， 后续任何未知失败都不得伪报未执行。owner 侧在 native offer 前 CLOSED/BUSY 回的错误使用 {@code daemonInstanceId = null}
 * 表示本节点没有当前已认证 Daemon，该 null 不更新或替代任何已确认的 daemon/terminal/stream 绑定。
 */
final class ShellGateway implements AutoCloseable {

  /** 每个浏览器连接最多登记的 (environment, viewer) 观察作用域数。 */
  static final int MAX_SCOPES_PER_CONNECTION = 64;

  /** 一个真实浏览器连接在网关上的一条观察会话；关闭清理缓存与观察，绝不关闭 PTY。 */
  interface Connection extends AutoCloseable {

    /** 处理该连接上解码出的一条 shell 控制命令。 */
    void receive(TerminalCommand command);

    @Override
    void close();
  }

  private record ScopeKey(UUID environmentId, UUID viewerId) {
    ScopeKey {
      Objects.requireNonNull(environmentId, "environmentId");
      Objects.requireNonNull(viewerId, "viewerId");
    }
  }

  private static final class Scope {
    /** 缓存的自权威 READY 路由；新 OPEN/ATTACH 与 resync 后为 null，必须重新解析。 */
    volatile EnvironmentTerminalRoute route;

    /** 每次新 OPEN/ATTACH 或 resync 递增：作废在途 route lookup 并切断旧绑定回执。 */
    long generation;

    /** 本次在途 OPEN/ATTACH 的请求标识与 ATTACH 声明的 identity（OPEN 为 null）。 */
    UUID requestId;

    TerminalIdentity requestIdentity;

    /** 仅由匹配本次请求的 ATTACHED 建立的绑定。 */
    TerminalIdentity terminalIdentity;

    UUID streamId;
    UUID daemonInstanceId;

    void clearBinding() {
      terminalIdentity = null;
      streamId = null;
      daemonInstanceId = null;
    }
  }

  private final NotificationBus bus;
  private final EnvironmentTerminalRouteSource routeSource;
  private final EnvironmentDaemonServer daemonServer;
  private final UUID self;
  private final Executor routeExecutor;
  private final Map<String, ConnectionState> connections = new ConcurrentHashMap<>();
  private final NotificationSubscription commandSubscription;
  private final NotificationSubscription eventSubscription;

  ShellGateway(
      NotificationBus bus,
      EnvironmentTerminalRouteSource routeSource,
      EnvironmentDaemonServer daemonServer,
      UUID self,
      Executor routeExecutor) {
    this.bus = Objects.requireNonNull(bus, "bus");
    this.routeSource = Objects.requireNonNull(routeSource, "routeSource");
    this.daemonServer = Objects.requireNonNull(daemonServer, "daemonServer");
    this.self = Objects.requireNonNull(self, "self");
    this.routeExecutor = Objects.requireNonNull(routeExecutor, "routeExecutor");
    // owner 侧没有任何本地状态，重连只需继续消费；浏览器侧重连则冻结缓存并通知连接恢复。
    this.commandSubscription = bus.subscribe(ShellTopics.COMMAND, this::onCommand, () -> {});
    this.eventSubscription = bus.subscribe(ShellTopics.EVENT, this::onEvent, this::onBusResync);
  }

  /** 为一个真实浏览器连接登记观察会话；同 connectionId 的旧会话被幂等关闭。 */
  Connection open(String connectionId, Consumer<TerminalEvent> sink, Runnable resync) {
    Objects.requireNonNull(connectionId, "connectionId");
    Objects.requireNonNull(sink, "sink");
    Objects.requireNonNull(resync, "resync");
    ConnectionState state = new ConnectionState(connectionId, sink, resync);
    ConnectionState previous = connections.put(connectionId, state);
    if (previous != null) {
      previous.close();
    }
    return state;
  }

  @Override
  public void close() {
    commandSubscription.close();
    eventSubscription.close();
    for (ConnectionState state : connections.values()) {
      state.close();
    }
    connections.clear();
  }

  /** owner 消费：只调用唯一的 {@link EnvironmentDaemonServer#sendShell}；CLOSED/BUSY 在真正递交前回固定未执行错误。 */
  private void onCommand(TerminalDispatch dispatch) {
    DaemonOfferResult result;
    try {
      result = daemonServer.sendShell(dispatch);
    } catch (RuntimeException error) {
      // 命令已经 publish：未知失败不得伪报未执行。对本节点发起的真实连接先走唯一恢复路径（冻结缓存并触发重连），
      // 再把异常交回 NotificationBus 既有失败/恢复处理（LocalInbox 只做去敏日志并 recover，不重试原命令）；
      // 跨节点未知失败本节点无从收敛，由浏览器控制器 pending deadline 恢复，绝不伪造 UUID 或 NOT_EXECUTED。
      TerminalRoute route = dispatch.request().route();
      if (self.equals(route.appNodeId())) {
        ConnectionState state = connections.get(route.connectionId());
        if (state != null) {
          state.onBusResync();
        }
      }
      throw error;
    }
    if (result == DaemonOfferResult.ACCEPTED) {
      return;
    }
    ErrorCode code =
        result == DaemonOfferResult.BUSY ? ErrorCode.BACKPRESSURE : ErrorCode.ROUTE_UNAVAILABLE;
    TerminalCommand command = dispatch.request().command();
    TerminalEvent event =
        new TerminalEvent(
            command.requestId(),
            command.environmentId(),
            command.viewerId(),
            null,
            new TerminalEvent.ErrorPayload(code, ErrorDisposition.NOT_EXECUTED));
    TerminalDelivery delivery =
        new TerminalDelivery(
            self,
            dispatch.leaseToken(),
            null,
            new TerminalResponse(dispatch.request().route(), event));
    bus.publish(
        ShellTopics.EVENT,
        NotificationAddress.node(dispatch.request().route().appNodeId()),
        delivery);
  }

  /** 投递一条已由 owner 回传的 shell 事件；只交给本节点上发起该绑定的真实连接。 */
  private void onEvent(TerminalDelivery delivery) {
    TerminalRoute route = delivery.response().route();
    if (!self.equals(route.appNodeId())) {
      return;
    }
    ConnectionState state = connections.get(route.connectionId());
    if (state != null) {
      state.deliver(delivery);
    }
  }

  /** Bus 重连：冻结全部连接缓存并通知连接恢复，绝不重发任何副作用。 */
  private void onBusResync() {
    for (ConnectionState state : connections.values()) {
      state.onBusResync();
    }
  }

  private final class ConnectionState implements Connection {

    private final String connectionId;
    private final Consumer<TerminalEvent> sink;
    private final Runnable resync;
    private final Object lock = new Object();
    private final Map<ScopeKey, Scope> scopes = new HashMap<>();
    private boolean closed;

    private ConnectionState(String connectionId, Consumer<TerminalEvent> sink, Runnable resync) {
      this.connectionId = connectionId;
      this.sink = sink;
      this.resync = resync;
    }

    @Override
    public void receive(TerminalCommand command) {
      Objects.requireNonNull(command, "command");
      ScopeKey key = new ScopeKey(command.environmentId(), command.viewerId());
      Scope scope;
      boolean overCapacity = false;
      synchronized (lock) {
        if (closed) {
          return;
        }
        scope = scopes.get(key);
        if (scope == null) {
          if (scopes.size() >= MAX_SCOPES_PER_CONNECTION) {
            overCapacity = true;
            scope = null;
          } else {
            scope = new Scope();
            scopes.put(key, scope);
          }
        }
      }
      if (overCapacity) {
        emitLocalError(command, ErrorCode.BACKPRESSURE, ErrorDisposition.NOT_EXECUTED);
        return;
      }
      boolean authoritative = requiresAuthoritativeRoute(command.payload());
      boolean needsFreshRoute;
      long generation;
      synchronized (lock) {
        if (closed || scopes.get(key) != scope) {
          return;
        }
        if (authoritative) {
          // 新 OPEN/ATTACH：登记本次请求与代次，立即作废旧显示流，并要求重新读取权威 READY route。
          scope.generation++;
          scope.requestId = command.requestId();
          scope.requestIdentity =
              command.payload() instanceof TerminalCommand.Attach attach ? attach.identity() : null;
          scope.clearBinding();
          scope.route = null;
        } else if (scope.route == null) {
          scope.generation++;
        }
        generation = scope.generation;
        needsFreshRoute = authoritative || scope.route == null;
      }
      if (needsFreshRoute) {
        long requestGeneration = generation;
        Scope requestScope = scope;
        try {
          routeExecutor.execute(
              () -> resolveThenPublish(key, requestScope, requestGeneration, command));
        } catch (RuntimeException rejected) {
          // executor 拒绝：明确未执行，绝不在 WebSocket 调用线程上做阻塞 DB 查询。
          emitIfCurrent(key, requestScope, requestGeneration, command, ErrorCode.BACKPRESSURE);
        }
        return;
      }
      publish(command, scope.route);
    }

    private void resolveThenPublish(
        ScopeKey key, Scope scope, long generation, TerminalCommand command) {
      EnvironmentTerminalRoute resolved;
      try {
        Optional<EnvironmentTerminalRoute> route =
            routeSource.resolveReadyRoute(command.environmentId());
        if (route.isEmpty()) {
          emitIfCurrent(key, scope, generation, command, ErrorCode.ROUTE_UNAVAILABLE);
          return;
        }
        resolved = route.get();
      } catch (RuntimeException error) {
        emitIfCurrent(key, scope, generation, command, ErrorCode.ROUTE_UNAVAILABLE);
        return;
      }
      synchronized (lock) {
        if (closed || scopes.get(key) != scope || scope.generation != generation) {
          // 旧 lookup 完成：不得复活已关闭/已被新请求或 resync 取代的缓存。
          return;
        }
        scope.route = resolved;
      }
      publish(command, resolved);
    }

    @Override
    public void close() {
      List<TerminalCommand> detaches = new ArrayList<>();
      List<EnvironmentTerminalRoute> detachRoutes = new ArrayList<>();
      synchronized (lock) {
        if (closed) {
          return;
        }
        closed = true;
        for (Map.Entry<ScopeKey, Scope> entry : scopes.entrySet()) {
          Scope scope = entry.getValue();
          EnvironmentTerminalRoute route = scope.route;
          TerminalIdentity identity = scope.terminalIdentity;
          UUID streamId = scope.streamId;
          if (route != null && identity != null && streamId != null) {
            TerminalCommand command =
                new TerminalCommand(
                    UUID.randomUUID(),
                    entry.getKey().environmentId(),
                    entry.getKey().viewerId(),
                    new TerminalCommand.Detach(identity, streamId));
            detaches.add(command);
            detachRoutes.add(route);
          }
        }
        scopes.clear();
      }
      // 退订观察：只对仍持有绑定与显示流的 scope 发 best-effort DETACH，绝不发 CLOSE、不杀 PTY、不重试。
      for (int index = 0; index < detaches.size(); index++) {
        publish(detaches.get(index), detachRoutes.get(index));
      }
      // 幂等释放注册：只有当前映射仍是本会话时才移除，同 connectionId 替换出的新会话绝不被旧会话误删。
      connections.remove(connectionId, this);
    }

    private void deliver(TerminalDelivery delivery) {
      TerminalEvent event = delivery.response().event();
      ScopeKey key = new ScopeKey(event.environmentId(), event.viewerId());
      boolean accept;
      synchronized (lock) {
        if (closed) {
          return;
        }
        Scope scope = scopes.get(key);
        if (scope == null) {
          return;
        }
        accept = matchesBinding(scope, delivery);
        if (accept) {
          applyBinding(scope, delivery);
        }
      }
      if (accept) {
        sink.accept(event);
      }
    }

    private boolean matchesBinding(Scope scope, TerminalDelivery delivery) {
      EnvironmentTerminalRoute route = scope.route;
      // 权威 route 围栏：owner 与 READY lease 都必须与缓存的权威路由一致，不能只比 lease。
      if (route == null
          || !route.leaseToken().equals(delivery.leaseToken())
          || !route.ownerNodeId().equals(delivery.ownerNodeId())) {
        return false;
      }
      TerminalEvent event = delivery.response().event();
      if (delivery.daemonInstanceId() != null
          && scope.daemonInstanceId != null
          && !scope.daemonInstanceId.equals(delivery.daemonInstanceId())) {
        return false;
      }
      TerminalIdentity identity = event.identity();
      if (identity != null
          && scope.terminalIdentity != null
          && !scope.terminalIdentity.equals(identity)) {
        return false;
      }
      TerminalEvent.Payload payload = event.payload();
      if (payload instanceof TerminalEvent.Attached) {
        // 只有本次 OPEN/ATTACH 请求的 ATTACHED 能建立绑定；ATTACH 还必须匹配声明 identity，旧 ATTACHED 不得改流。
        return scope.requestId != null
            && scope.requestId.equals(event.requestId())
            && (scope.requestIdentity == null || scope.requestIdentity.equals(identity));
      }
      if (payload instanceof TerminalEvent.ViewUpdate viewUpdate) {
        // 显示流严格匹配本次绑定；发起新 attach 后旧流立即失效，旧 VIEW_UPDATE 不能复活旧绑定。
        UUID streamId = scope.streamId;
        return streamId != null && streamId.equals(viewUpdate.update().streamId());
      }
      if (payload instanceof TerminalEvent.ErrorPayload) {
        // 本次关联请求的固定错误（如当前 ATTACH 的 DAEMON_MISMATCH）必须可见；已绑定终端的错误也可见
        // （含无 identity 的确定未执行）。原绑定的迟到错误（requestId 不同、identity 不同）被丢弃，
        // 且错误绝不建立新 stream。
        if (scope.requestId != null && scope.requestId.equals(event.requestId())) {
          return true;
        }
        if (scope.terminalIdentity == null) {
          return false;
        }
        return identity == null || identity.equals(scope.terminalIdentity);
      }
      // EXITED / WRITER_CHANGED / OP_ACK：不按显示流过滤，但必须已有绑定且 terminal 身份一致。
      return scope.terminalIdentity != null
          && identity != null
          && identity.equals(scope.terminalIdentity);
    }

    private void applyBinding(Scope scope, TerminalDelivery delivery) {
      TerminalEvent event = delivery.response().event();
      if (event.payload() instanceof TerminalEvent.Attached attached) {
        scope.terminalIdentity = event.identity();
        scope.streamId = attached.streamId();
        if (delivery.daemonInstanceId() != null) {
          scope.daemonInstanceId = delivery.daemonInstanceId();
        }
      }
      // 其它事件（含 ERROR）绝不改动绑定，尤其是不得用错误携带的 identity 建立新的 terminal/stream。
    }

    private void onBusResync() {
      synchronized (lock) {
        if (closed) {
          return;
        }
        // 冻结：丢弃缓存 owner/lease 与全部绑定并递增代次，令在途 lookup 与迟到回执都无法复活旧绑定；
        // 恢复只由浏览器显式重新 ATTACH 驱动，绝不重发副作用。
        for (Scope scope : scopes.values()) {
          scope.generation++;
          scope.route = null;
          scope.requestId = null;
          scope.requestIdentity = null;
          scope.clearBinding();
        }
      }
      resync.run();
    }

    private void emitIfCurrent(
        ScopeKey key, Scope scope, long generation, TerminalCommand command, ErrorCode code) {
      synchronized (lock) {
        if (closed || scopes.get(key) != scope || scope.generation != generation) {
          return;
        }
      }
      emitLocalError(command, code, ErrorDisposition.NOT_EXECUTED);
    }

    private void emitLocalError(
        TerminalCommand command, ErrorCode code, ErrorDisposition disposition) {
      TerminalEvent event =
          new TerminalEvent(
              command.requestId(),
              command.environmentId(),
              command.viewerId(),
              null,
              new TerminalEvent.ErrorPayload(code, disposition));
      sink.accept(event);
    }

    private void publish(TerminalCommand command, EnvironmentTerminalRoute route) {
      TerminalRequest request = new TerminalRequest(new TerminalRoute(self, connectionId), command);
      TerminalDispatch dispatch = new TerminalDispatch(route.leaseToken(), request);
      bus.publish(ShellTopics.COMMAND, NotificationAddress.node(route.ownerNodeId()), dispatch);
    }
  }

  /** OPEN/ATTACH 必须重新读取权威 READY route；其余命令可用已缓存绑定。 */
  private static boolean requiresAuthoritativeRoute(TerminalCommand.Payload payload) {
    return payload instanceof TerminalCommand.Open || payload instanceof TerminalCommand.Attach;
  }
}
