package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.List;

/** 验证 Tool admission 在路由副作用前拒绝，并覆盖 terminal/cancel/submit 异常释放。 */
class ToolExecutionGatewayAdmissionTest {

  @Test
  void hostCapacityRejectsBeforeToolAndCancelReleasesPermit() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.hostDescriptor("demo");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(descriptor);
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
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
                    ToolGatewayTestSupport.hostRequest("call-1", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));
    ToolGateway.RetryLater second =
        assertInstanceOf(
            ToolGateway.RetryLater.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.hostRequest("call-2", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));

    assertEquals(ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY.get(), second.retryAfter());
    assertEquals(0, tool.requests.size());
    first.handle().cancel();

    ToolGateway.Started third =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.hostRequest("call-3", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));
    third.handle().cancel();
  }

  @Test
  void fullCapacityRejectsBeforeContributorLookupAndRemoteInvoke() {
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ConcurrencyAdmission.Lease occupied = admission.tryAcquire().orElseThrow();
    try {
      ToolDescriptor hostDescriptor = ToolGatewayTestSupport.hostDescriptor("factory-tool");
      ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
      ToolExecutionGateway gateway =
          ToolGatewayTestSupport.gateway(
              ToolGatewayTestSupport.defaultCatalog(
                  new ToolGatewayTestSupport.FakeTool(hostDescriptor)),
              transport,
              new ToolGatewayTestSupport.FakeResourceStore(),
              new ToolGatewayTestSupport.DirectQueueExecutor(),
              ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
              ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
              admission);

      assertInstanceOf(
          ToolGateway.RetryLater.class,
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.hostRequest("host", hostDescriptor)),
              new ToolGatewayTestSupport.RecordingListener()));
      assertInstanceOf(
          ToolGateway.RetryLater.class,
          gateway.start(effectsExecution(), new ToolGatewayTestSupport.RecordingListener()));
      assertInstanceOf(
          ToolGateway.RetryLater.class,
          gateway.start(
              ToolGatewayTestSupport.execution(
                  ToolGatewayTestSupport.environmentRequest(
                      "environment", ToolGatewayTestSupport.ENV_A)),
              new ToolGatewayTestSupport.RecordingListener()));

      assertTrue(
          transport.invocations.isEmpty(), "full admission must not invoke remote transport");
    } finally {
      occupied.close();
    }
  }

  @Test
  void deterministicRejectReleasesTopLevelPermit() {
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.hostRequest(
                        "missing", ToolGatewayTestSupport.hostDescriptor("missing"))),
                new ToolGatewayTestSupport.RecordingListener()));

    assertEquals(ToolExecutionGateway.TOOL_NOT_FOUND_KIND, rejected.error().kind());
    ConcurrencyAdmission.Lease recovered = admission.tryAcquire().orElseThrow();
    recovered.close();
  }

  @Test
  void terminalAndThrowingListenerReleasePermitForNextLocalTool() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.hostDescriptor("demo");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(descriptor);
    tool.handler =
        (request, listener) ->
            listener.onComplete(ToolGatewayTestSupport.result(request.call().id(), "done"));
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
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
                    ToolGatewayTestSupport.hostRequest("call-1", descriptor)),
                listener));
    first.handle().activate();
    executor.drain();
    assertEquals(1, listener.terminalInvocations.get());

    ToolGateway.Started second =
        assertInstanceOf(
            ToolGateway.Started.class,
            gateway.start(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.hostRequest("call-2", descriptor)),
                new ToolGatewayTestSupport.RecordingListener()));
    second.handle().cancel();
  }

  @Test
  void remoteCapacityRejectsBeforeTransportAndCancelReleasesPermit() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            new ToolGatewayTestSupport.DirectQueueExecutor(),
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
        ToolGateway.RetryLater.class,
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-2", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener()));

    first.handle().cancel();
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
  void synchronousRemoteTerminalReleasesPermitAfterActivation() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "done");
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor,
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
        ToolGateway.RetryLater.class,
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.environmentRequest("call-2", ToolGatewayTestSupport.ENV_A)),
            new ToolGatewayTestSupport.RecordingListener()));

    first.handle().activate();
    executor.drain();
    assertEquals(1, listener.events.size());
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
    ToolDescriptor descriptor = ToolGatewayTestSupport.hostDescriptor("demo");
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(descriptor);
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStore(),
            executor,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);
    executor.rejectSubmissions = true;

    assertInstanceOf(
        ToolGateway.RetryLater.class,
        gateway.start(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", descriptor)),
            new ToolGatewayTestSupport.RecordingListener()));
    ConcurrencyAdmission.Lease lease = admission.tryAcquire().orElseThrow();
    lease.close();
  }

  private static ToolGateway.Execution effectsExecution() {
    ToolDescriptor descriptor = ToolGatewayTestSupport.hostDescriptor("effects-tool");
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("effects", descriptor.name(), "{}"),
            new ToolBinding(
                new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE),
                new ContributorBinding("goal", "tool", List.of()),
                EnvironmentSupport.NONE,
                null,
                null));
    return ToolGatewayTestSupport.execution(request);
  }
}
