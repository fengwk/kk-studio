package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;

import java.util.List;

/** 验证 Tool admission 在路由副作用前拒绝，并覆盖 terminal/cancel/submit 异常释放。 */
class PlatformToolGatewayAdmissionTest {

  @Test
  void platformCapacityRejectsBeforeToolAndCancelReleasesPermit() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.platformDescriptor("demo");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(descriptor);
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    ToolGateway.Started first =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.platformRequest("call-1", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));
    ToolGateway.Overloaded second =
        assertInstanceOf(
            ToolGateway.Overloaded.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.platformRequest("call-2", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));

    // 第 N+1 次在 Tool.execute 前拒绝，既不触碰 Tool，也不向 executor 增加执行副作用。
    assertEquals(ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY.get(), second.retryAfter());
    assertEquals(0, tool.requests.size());
    first.handle().cancel();

    ToolGateway.Started third =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.platformRequest("call-3", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));
    third.handle().cancel();
  }

  @Test
  void terminalAndThrowingListenerReleasePermitForNextLocalTool() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.platformDescriptor("demo");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(descriptor);
    tool.handler =
        (request, listener) ->
            listener.onComplete(ToolGatewayTestSupport.result(request.call().id(), "done"));
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    listener.throwOnSucceeded = true;

    ToolGateway.Started first =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.platformRequest("call-1", descriptor)),
                listener));
    first.handle().activate();
    executor.runAll();
    assertEquals(1, listener.terminalInvocations.get());

    // 同步 terminal 的 listener 异常不能使 lease 留在第一条 invocation 上。
    ToolGateway.Started second =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.platformRequest("call-2", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));
    second.handle().cancel();
  }

  @Test
  void remoteCapacityRejectsBeforeTransportAndCancelReleasesPermit() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            new ToolFactories(List.of()),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    ToolGateway.Started first =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-1", ToolGatewayTestSupport.ENV_A)),
                new ToolGatewayTestSupport.RecordingListener()));
    assertInstanceOf(
        ToolGateway.Overloaded.class,
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-2", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener()));
    assertEquals(1, transport.invocations.size());

    first.handle().cancel();
    ToolGateway.Started third =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-3", ToolGatewayTestSupport.ENV_A)),
                new ToolGatewayTestSupport.RecordingListener()));
    assertEquals(2, transport.invocations.size());
    third.handle().cancel();
  }

  @Test
  void synchronousRemoteTerminalReleasesPermitAfterActivation() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "done");
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            new ToolFactories(List.of()),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();

    ToolGateway.Started first =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-1", ToolGatewayTestSupport.ENV_A)),
                listener));
    assertInstanceOf(
        ToolGateway.Overloaded.class,
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-2", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener()));

    first.handle().activate();
    listener.awaitCount(1);
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.RETURN_HANDLE;
    ToolGateway.Started third =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-3", ToolGatewayTestSupport.ENV_A)),
                new ToolGatewayTestSupport.RecordingListener()));
    third.handle().cancel();
  }

  @Test
  void rejectedExecutorReleasesPermit() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.platformDescriptor("demo");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(descriptor);
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.factories(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);
    executor.reject = true;

    assertInstanceOf(
        ToolGateway.Overloaded.class,
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.platformRequest("call-1", descriptor)),
            new ToolGatewayTestSupport.RecordingListener()));
    ConcurrencyAdmission.Lease lease = admission.tryAcquire().orElseThrow();
    lease.close();
  }
}
