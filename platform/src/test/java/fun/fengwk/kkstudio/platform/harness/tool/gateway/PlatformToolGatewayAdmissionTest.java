package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.platform.harness.tool.ToolContributionCatalog;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
  void fullCapacityRejectsBeforeFactoryPluginLookupAndRemoteInvoke() {
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ConcurrencyAdmission.Lease occupied = admission.tryAcquire().orElseThrow();
    try {
      ToolDescriptor platformDescriptor = ToolGatewayTestSupport.platformDescriptor("factory-tool");
      AtomicInteger creates = new AtomicInteger();
      ToolFactory factory =
          new ToolFactory() {
            @Override
            public ToolDescriptor descriptor() {
              return platformDescriptor;
            }

            @Override
            public ToolGatewayTestSupport.FakeTool create() {
              creates.incrementAndGet();
              return new ToolGatewayTestSupport.FakeTool(platformDescriptor);
            }
          };
      PluginCatalog pluginCatalog = PluginCatalog.from(List.of());
      ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
      PlatformToolGateway gateway =
          ToolGatewayTestSupport.gateway(
              new ToolContributionCatalog(List.of(factory), PluginCatalog.from(List.of())),
              pluginCatalog,
              transport,
              new ToolGatewayTestSupport.FakeResourceStore(),
              new ToolGatewayTestSupport.ManualExecutor(),
              ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
              ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
              admission);

      // 顶层容量已满：三种路由都必须在 factory/plugin/Environment/remote 副作用前确定性 Overloaded。
      assertInstanceOf(
          ToolGateway.Overloaded.class,
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.platformRequest("platform", platformDescriptor)),
              new ToolGatewayTestSupport.RecordingListener()));
      assertInstanceOf(
          ToolGateway.Overloaded.class,
          gateway.start(pluginExecution(), new ToolGatewayTestSupport.RecordingListener()));
      assertInstanceOf(
          ToolGateway.Overloaded.class,
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.environmentRequest(
                      "environment", ToolGatewayTestSupport.ENV_A)),
              new ToolGatewayTestSupport.RecordingListener()));

      assertEquals(0, creates.get(), "full admission must not call ToolFactory.create");
      assertTrue(
          transport.invocations.isEmpty(), "full admission must not invoke remote transport");
    } finally {
      occupied.close();
    }
  }

  @Test
  void deterministicRejectReleasesTopLevelPermit() {
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            new ToolContributionCatalog(List.of(), PluginCatalog.from(List.of())),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.platformRequest(
                        "missing", ToolGatewayTestSupport.platformDescriptor("missing"))),
                new ToolGatewayTestSupport.RecordingListener()));

    assertEquals(PlatformToolGateway.TOOL_NOT_FOUND_KIND, rejected.error().kind());
    ConcurrencyAdmission.Lease recovered = admission.tryAcquire().orElseThrow();
    recovered.close();
  }

  @Test
  void unexpectedTopLevelFailureReleasesPermit() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.platformDescriptor("unexpected");
    ToolFactory factory =
        new ToolFactory() {
          @Override
          public ToolDescriptor descriptor() {
            return descriptor;
          }

          @Override
          public ToolGatewayTestSupport.FakeTool create() {
            throw new IllegalStateException("unexpected factory failure");
          }
        };
    ToolContributionCatalog contributions =
        new ToolContributionCatalog(List.of(factory), PluginCatalog.from(List.of()));
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    PlatformToolGateway gateway =
        ToolGatewayTestSupport.gateway(
            contributions,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.ManualExecutor(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    assertThrows(
        IllegalStateException.class,
        () ->
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.platformRequest("unexpected", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));
    ConcurrencyAdmission.Lease recovered = admission.tryAcquire().orElseThrow();
    recovered.close();
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
            new ToolContributionCatalog(List.of(), PluginCatalog.from(List.of())),
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
            new ToolContributionCatalog(List.of(), PluginCatalog.from(List.of())),
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

  private static ToolGateway.Execution pluginExecution() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.platformDescriptor("plugin-tool");
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("plugin", descriptor.name(), "{}"),
            new ToolBinding(
                descriptor,
                ToolType.PLATFORM,
                null,
                new PluginToolBinding("plugin", "tool", List.of())));
    return ToolGatewayTestSupport.execution(request);
  }
}
