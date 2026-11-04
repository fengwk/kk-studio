package fun.fengwk.kkstudio.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.daemon.DaemonIdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeCommand;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeLease;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeLeaseBinding;
import fun.fengwk.kkstudio.platform.daemon.protocol.IdeResult;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.platform.daemon.server.DaemonIdeCall;
import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class IdeCommandTransportTest {

  /** 命令句柄和租约身份跨边界保持不变，绑定必须序列化为协议字段。 */
  @Test
  void delegatesCommandAndSerializesLease() {
    EnvironmentDaemonServer server = mock(EnvironmentDaemonServer.class);
    IdeCommandTransport transport = new IdeCommandTransport(server);
    EnvironmentId environment = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
    DaemonConnectionIdentity identity = new DaemonConnectionIdentity(environment, "daemon");
    UUID invocation = UUID.randomUUID();
    UUID view = UUID.randomUUID();
    UUID document = UUID.randomUUID();
    IdeCommand command = IdeCommand.newBuilder().build();
    CompletableFuture<IdeResult> reply = new CompletableFuture<>();
    when(server.requestIde(environment, command))
        .thenReturn(new DaemonIdeCall(identity, invocation, reply));

    IdeRequest request = transport.requestIde(environment, command);
    assertEquals(identity, request.identity());
    assertEquals(invocation, request.invocationId());
    assertSame(reply, request.reply());

    transport.sendIdeLease(
        environment,
        identity,
        new DaemonIdeLease(42L, List.of(new DaemonIdeLease.Binding(view, document, 7L))));
    IdeLease expected =
        IdeLease.newBuilder()
            .setRound(42L)
            .addBindings(
                IdeLeaseBinding.newBuilder()
                    .setViewId(view.toString())
                    .setDocumentId(document.toString())
                    .setBindingEpoch(7L))
            .build();
    verify(server).sendIdeLease(environment, identity, expected);
    transport.abandonIde(invocation);
    verify(server).abandonIde(invocation);
  }
}
