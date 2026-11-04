package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.TurnMediaCapabilities;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionAction;
import fun.fengwk.kkstudio.platform.vfs.VfsEditRejectedException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ToolExecutionGateway#admit}：纯本地 admission——容量 Busy、确定性 Rejected、Admitted 持有资源并由 {@code
 * Run.close} 恰好释放一次， 以及请求装配（冻结环境路由、一次性超时解析、edit review 的 expectedFile）。
 */
class ToolExecutionGatewayAdmitTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");

  @Test
  void hostRouteAdmitsWithoutExecutingAndRunSeesExactRequestAndContext() throws Exception {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolInvocationRequest request = ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            transport,
            new ToolGatewayTestSupport.FakeResourceStager());

    ToolGateway.Run run = ToolGatewayTestSupport.admitted(gateway, request);

    assertTrue(tool.requests.isEmpty(), "admission must never execute the tool");
    run.execute(partial -> {});
    run.close();
    ToolExecutionRequest executed = tool.requests.get(0);
    assertEquals(DESCRIPTOR, executed.descriptor());
    assertSame(request.call(), executed.call());
    // host 工具没有 arguments 级超时契约：解析结果就是 definition 默认值。
    assertEquals(DESCRIPTOR.defaultTimeout(), executed.timeout());
    assertEquals(ToolGatewayTestSupport.INVOCATION_ID, executed.context().invocationId());
    assertEquals(ToolGatewayTestSupport.THREAD_ID, executed.context().threadId());
    assertEquals(ToolGatewayTestSupport.SESSION_ID, executed.context().sessionId());
    assertEquals(ToolGatewayTestSupport.ROOT_THREAD_ID, executed.context().rootThreadId());
    assertEquals(ToolGatewayTestSupport.ROOT_SESSION_ID, executed.context().rootSessionId());
    // 网关必须原样传递该调用冻结的 assistant 入口，产品据此判断调用归属。
    assertEquals(ToolGatewayTestSupport.ASSISTANT_ENTRY_ID, executed.context().assistantEntryId());
    assertEquals(TurnMediaCapabilities.NONE, executed.context().mediaCapabilities());
    assertSame(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW, executed.context().branch());
    assertTrue(transport.invocations.isEmpty());
  }

  /** 超时只在 admission 解析一次：该精确 Duration 原样交给工具实现，执行阶段不再二次解析。 */
  @Test
  void timeoutIsResolvedExactlyOnceAtAdmission() throws Exception {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.resolvedTimeout = Duration.ofSeconds(7200);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager());

    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway, ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR));
    assertEquals(1, tool.resolveTimeoutCalls.get());
    run.execute(partial -> {});
    run.close();

    assertEquals(Duration.ofSeconds(7200), tool.requests.get(0).timeout());
    assertEquals(1, tool.resolveTimeoutCalls.get());
  }

  /** 非法 arguments 级超时 fail closed：确定性 INVALID_REQUEST，释放租约且不触达 transport。 */
  @Test
  void invalidTimeoutIsRejectedAndReleasesResources() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.admit(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.environmentRequest(
                        "call-1",
                        ToolGatewayTestSupport.ENV_A,
                        "{\"command\":\"ls\",\"workdir\":\"/home/dev\",\"timeout_seconds\":0}"))));

    assertEquals(ToolExecutionGateway.INVALID_REQUEST_KIND, rejected.error().kind());
    assertTrue(transport.invocations.isEmpty());
    admission.tryAcquire().orElseThrow().close();
  }

  /** 环境路由只使用冻结的 EnvironmentId；显式 timeout_seconds 严格覆盖 capability 默认值（更短与更长都原样传递）。 */
  @Test
  void environmentRouteUsesFrozenEnvironmentIdAndExplicitTimeout() throws Exception {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-1", "ok");
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStager());
    String base = "{\"command\":\"ls\",\"workdir\":\"/home/dev\"";
    List<ToolInvocationRequest> requests =
        List.of(
            ToolGatewayTestSupport.environmentRequest(
                "call-a", ToolGatewayTestSupport.ENV_A, base + "}"),
            ToolGatewayTestSupport.environmentRequest(
                "call-b", ToolGatewayTestSupport.ENV_B, base + ",\"timeout_seconds\":7}"),
            ToolGatewayTestSupport.environmentRequest(
                "call-c", ToolGatewayTestSupport.ENV_A, base + ",\"timeout_seconds\":7200}"));
    for (ToolInvocationRequest request : requests) {
      ToolGateway.Run run = ToolGatewayTestSupport.admitted(gateway, request);
      run.execute(partial -> {});
      run.close();
    }

    assertEquals(ToolGatewayTestSupport.ENV_A, transport.invocations.get(0).environmentId());
    assertEquals(ToolGatewayTestSupport.ENV_B, transport.invocations.get(1).environmentId());
    // 每次实际远端调用使用独立 UUID，不复用 ToolInvocation id。
    assertFalse(
        ToolGatewayTestSupport.INVOCATION_ID
            .toString()
            .equals(transport.invocations.get(0).request().call().id()));
    assertEquals(
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC),
        transport.invocations.get(0).request().descriptor());
    assertEquals(
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC)
            .defaultTimeout(),
        transport.invocations.get(0).request().timeout());
    assertEquals(Duration.ofSeconds(7), transport.invocations.get(1).request().timeout());
    assertEquals(Duration.ofSeconds(7200), transport.invocations.get(2).request().timeout());
  }

  /** 容量已满：Busy（肯定未执行），携带 live 的 retryAfter，且不触达工具；释放 Run 后容量恢复。 */
  @Test
  void fullCapacityIsBusyUntilTheRunIsClosed() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);
    ToolInvocationRequest request = ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);

    ToolGateway.Run first = ToolGatewayTestSupport.admitted(gateway, request);
    ToolGateway.Busy busy =
        assertInstanceOf(
            ToolGateway.Busy.class, gateway.admit(ToolGatewayTestSupport.execution(request)));
    assertEquals(ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY.get(), busy.retryAfter());
    assertTrue(tool.requests.isEmpty());

    first.close();
    first.close();
    ToolGateway.Run second = ToolGatewayTestSupport.admitted(gateway, request);
    second.close();
    admission.tryAcquire().orElseThrow().close();
  }

  /** 满容量在目录查找之前拒绝：既不租用目录也不执行工具。 */
  @Test
  void fullCapacityBusyDoesNotAcquireCatalogOrExecute() {
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ConcurrencyAdmission.Lease held = admission.tryAcquire().orElseThrow();
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            admission);

    assertInstanceOf(
        ToolGateway.Busy.class,
        gateway.admit(
            ToolGatewayTestSupport.execution(
                ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR))));

    assertTrue(tool.requests.isEmpty(), "admission must never execute the tool");
    held.close();
  }

  /** 缺失工具、定义不一致、贡献身份不一致都是确定性 Rejected，释放并发配额且不触达工具 / transport。 */
  @Test
  void frozenContributionMismatchesAreRejectedAndReleaseTheQuota() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            transport,
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    // 目录中不存在的工具。
    ToolDescriptor missing = ToolGatewayTestSupport.hostDescriptor("missing");
    ToolGateway.Rejected notFound =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.admit(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.hostRequest("call-1", missing))));
    assertEquals(ToolExecutionGateway.TOOL_NOT_FOUND_KIND, notFound.error().kind());

    // 冻结定义与目录当前定义不一致。
    ToolDescriptor drifted =
        new ToolDescriptor(
            DESCRIPTOR.name(),
            "drifted description",
            DESCRIPTOR.rendererKey(),
            new InputSchema("arguments", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofMinutes(1));
    ToolGateway.Rejected mismatch =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.admit(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.hostRequest("call-2", drifted))));
    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, mismatch.error().kind());
    assertTrue(
        mismatch.error().message().contains(ToolErrorGuidance.ExecutionFact.NOT_EXECUTED.fact()));

    // 贡献身份（provenance）不一致。
    ToolInvocationRequest hostRequest = ToolGatewayTestSupport.hostRequest("call-3", DESCRIPTOR);
    ToolInvocationRequest forged =
        new ToolInvocationRequest(
            hostRequest.call(),
            new ToolBinding(
                hostRequest.binding().definition(),
                new ContributorBinding("test", "forged-name", List.of()),
                EnvironmentSupport.NONE,
                null,
                null));
    assertInstanceOf(
        ToolGateway.Rejected.class, gateway.admit(ToolGatewayTestSupport.execution(forged)));

    assertTrue(tool.requests.isEmpty());
    assertTrue(transport.invocations.isEmpty());
    admission.tryAcquire().orElseThrow().close();
  }

  /** 工具实例的 descriptor 在冻结后被改写或变为 null：branch 加载之前确定性拒绝。 */
  @Test
  void descriptorDriftAfterFreezeIsRejectedBeforeBranchLoad() {
    AtomicReference<ToolDescriptor> live = new AtomicReference<>(DESCRIPTOR);
    ToolGatewayTestSupport.FakeTool tool =
        new ToolGatewayTestSupport.FakeTool(DESCRIPTOR) {
          @Override
          public ToolDescriptor descriptor() {
            return live.get();
          }
        };
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            new ConcurrencyAdmission(Integer.MAX_VALUE));
    ToolInvocationRequest request = ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR);
    live.set(ToolGatewayTestSupport.hostDescriptor("other"));

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class, gateway.admit(ToolGatewayTestSupport.execution(request)));
    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
    assertTrue(tool.requests.isEmpty(), "rejected admission must never execute the tool");
  }

  /** branch 未选择 Environment 的环境工具：admission 阶段仍 fail-safe 为 ENVIRONMENT_NOT_SELECTED。 */
  @Test
  void unselectedEnvironmentToolIsRejectedWithoutTouchingTransport() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            transport,
            new ToolGatewayTestSupport.FakeResourceStager());

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.admit(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.unselectedEnvironmentRequest("call-1"))));

    assertEquals("ENVIRONMENT_NOT_SELECTED", rejected.error().kind());
    assertTrue(rejected.error().message().contains("Select an Environment"));
    assertTrue(transport.invocations.isEmpty());
  }

  /** 目录租用失败（admit 抛出）：释放并发配额，异常原样传播，没有任何资源残留。 */
  @Test
  void catalogAcquisitionFailureReleasesQuotaAndPropagates() {
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    AtomicInteger acquires = new AtomicInteger();
    RuntimeToolCatalogSource failing =
        new RuntimeToolCatalogSource() {
          @Override
          public Lease acquire() {
            acquires.incrementAndGet();
            throw new IllegalStateException("catalog unavailable");
          }
        };
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            failing,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);

    assertThrows(
        IllegalStateException.class,
        () ->
            gateway.admit(
                ToolGatewayTestSupport.execution(
                    ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR))));

    assertEquals(1, acquires.get());
    admission.tryAcquire().orElseThrow().close();
  }

  /** 必需的 edit 审批没有有效 review：确定性拒绝，transport 不被触达，租约释放。 */
  @Test
  void requiredEditWithoutReviewIsRejectedWithoutTouchingTransport() {
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog();
    ToolInvocationRequest request = editRequest(catalog, "call-edit-1");
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            catalog, transport, new ToolGatewayTestSupport.FakeResourceStager(), admission);

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.admit(
                new ToolGateway.Execution(
                    ToolGatewayTestSupport.INVOCATION_ID,
                    ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW),
                    1,
                    request,
                    approved(null),
                    new ToolGatewayTestSupport.RecordingRemoteExecutions())));

    assertEquals(ToolExecutionGateway.EDIT_REVIEW_REQUIRED_KIND, rejected.error().kind());
    assertTrue(
        rejected.error().message().contains(ToolErrorGuidance.ExecutionFact.NOT_EXECUTED.fact()));
    assertTrue(
        rejected
            .error()
            .message()
            .contains("Request approval with an edit review before calling the tool again"));
    assertTrue(transport.invocations.isEmpty());
    admission.tryAcquire().orElseThrow().close();
  }

  /** 有效 review：canonical ExpectedFile 原样进入 capability 请求；自动放行的 edit 不携带 expectedFile。 */
  @Test
  void editExpectedFileReachesTheTransportOnlyWhenReviewed() throws Exception {
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog();
    ToolInvocationRequest request = editRequest(catalog, "call-edit-2");
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = ToolGatewayTestSupport.result("call-edit-2", "ok");
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            catalog, transport, new ToolGatewayTestSupport.FakeResourceStager());
    ExpectedFile expectedFile =
        new ExpectedFile(
            "/canonical/workspace/a.txt",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    ToolGateway.Run reviewed =
        assertInstanceOf(
                ToolGateway.Admitted.class,
                gateway.admit(
                    new ToolGateway.Execution(
                        ToolGatewayTestSupport.INVOCATION_ID,
                        ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW),
                        1,
                        request,
                        approved(new ToolEditReview(expectedFile, "-old\n+new")),
                        new ToolGatewayTestSupport.RecordingRemoteExecutions())))
            .run();
    reviewed.execute(partial -> {});
    reviewed.close();
    assertEquals(expectedFile, transport.invocations.get(0).request().expectedFile());

    ToolGateway.Run auto = ToolGatewayTestSupport.admitted(gateway, request);
    assertNull(ToolGatewayTestSupport.execution(request).expectedFile());
    auto.execute(partial -> {});
    auto.close();
    assertNull(transport.invocations.get(1).request().expectedFile());
  }

  /** 非环境的同名 edit 工具不被审查守卫误拦：守卫基于真实贡献身份而非仅工具名。 */
  @Test
  void nonEnvironmentSameNamedEditIsNotBlockedByTheGuard() throws Exception {
    ToolDescriptor hostEditDescriptor = ToolGatewayTestSupport.hostDescriptor("edit");
    ToolGatewayTestSupport.FakeTool hostEdit =
        new ToolGatewayTestSupport.FakeTool(hostEditDescriptor);
    HarnessCatalog catalog =
        HarnessCatalog.from(
            List.of(
                HarnessContributor.of(
                    new ContributorDescriptor(
                        new ContributorId("host-contrib"), "HostContrib", "1", Set.of()),
                    registrar ->
                        registrar.registerTool("edit", hostEdit, ToolVisibility.SELECTABLE, 0))));
    ToolContribution contribution = catalog.findTool("edit").orElseThrow();
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-host-edit", "edit", "{}"),
            new ToolBinding(
                contribution.definition(),
                new ContributorBinding("host-contrib", "edit", List.of()),
                EnvironmentSupport.NONE,
                null,
                null));
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            catalog, transport, new ToolGatewayTestSupport.FakeResourceStager());

    ToolGateway.Run run =
        assertInstanceOf(
                ToolGateway.Admitted.class,
                gateway.admit(
                    new ToolGateway.Execution(
                        ToolGatewayTestSupport.INVOCATION_ID,
                        ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW),
                        1,
                        request,
                        approved(null),
                        new ToolGatewayTestSupport.RecordingRemoteExecutions())))
            .run();
    run.execute(partial -> {});
    run.close();

    assertEquals("edit", hostEdit.requests.get(0).descriptor().name());
    assertTrue(transport.invocations.isEmpty());
  }

  /** vfs: 编辑的审批携带 VFS 预览快照：admit 在执行前调用 verifySnapshot，expectedFile 来自审批。 */
  @Test
  void vfsEditApprovalVerifiesThePreviewSnapshotBeforeExecution() {
    ToolGatewayTestSupport.FakeVfsEditReviewSource source =
        new ToolGatewayTestSupport.FakeVfsEditReviewSource();
    String path = "vfs:/sessions/" + ToolGatewayTestSupport.ROOT_SESSION_ID + "/a.txt";
    source.review =
        new ToolEditReview(new ExpectedFile(path, "a".repeat(64)), "--- a\n+++ b\n-old\n+new");
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            source);
    ToolInvocationRequest request = ToolGatewayTestSupport.vfsEditRequest("call-vfs-edit", path);

    ToolGateway.Admitted admitted =
        assertInstanceOf(
            ToolGateway.Admitted.class,
            gateway.admit(
                new ToolGateway.Execution(
                    ToolGatewayTestSupport.INVOCATION_ID,
                    ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW),
                    1,
                    request,
                    approved(source.review),
                    new ToolGatewayTestSupport.RecordingRemoteExecutions())));

    admitted.run().close();
    assertEquals(List.of(source.review.expectedFile()), source.verified);
    assertEquals(List.of(ToolGatewayTestSupport.ROOT_SESSION_ID), source.verifiedRootSessions);
    assertEquals(List.of(ToolGatewayTestSupport.ROOT_THREAD_ID), source.verifiedRootThreads);
  }

  /** 审批后文件已变化：admit 拒绝执行，要求重新审查。 */
  @Test
  void staleVfsEditSnapshotIsRejectedBeforeExecution() {
    ToolGatewayTestSupport.FakeVfsEditReviewSource source =
        new ToolGatewayTestSupport.FakeVfsEditReviewSource();
    String path = "vfs:/sessions/" + ToolGatewayTestSupport.ROOT_SESSION_ID + "/a.txt";
    source.review =
        new ToolEditReview(new ExpectedFile(path, "a".repeat(64)), "--- a\n+++ b\n-old\n+new");
    source.verifyFailure =
        new VfsEditRejectedException(
            ToolErrorGuidance.message(
                path + " changed after this edit was approved",
                ToolErrorGuidance.ExecutionFact.NOT_EXECUTED,
                "Review and approve the edit again against the current file contents"));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager(),
            source);

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.admit(
                new ToolGateway.Execution(
                    ToolGatewayTestSupport.INVOCATION_ID,
                    ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW),
                    1,
                    ToolGatewayTestSupport.vfsEditRequest("call-vfs-edit", path),
                    approved(source.review),
                    new ToolGatewayTestSupport.RecordingRemoteExecutions())));
    assertEquals(EditReviewPreflight.FAILED_KIND, rejected.error().kind());
    assertTrue(rejected.error().message().contains("changed after this edit was approved"));
  }

  /** 已审批的 vfs: 编辑缺失审查事实时仍拒绝执行，与环境的编辑审查守卫一致。 */
  @Test
  void vfsEditApprovalWithoutReviewIsRejected() {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(),
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager());
    String path = "vfs:/sessions/" + ToolGatewayTestSupport.ROOT_SESSION_ID + "/a.txt";

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            gateway.admit(
                new ToolGateway.Execution(
                    ToolGatewayTestSupport.INVOCATION_ID,
                    ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW),
                    1,
                    ToolGatewayTestSupport.vfsEditRequest("call-vfs-edit", path),
                    approved(null),
                    new ToolGatewayTestSupport.RecordingRemoteExecutions())));
    assertEquals(ToolExecutionGateway.EDIT_REVIEW_REQUIRED_KIND, rejected.error().kind());
  }

  private static ToolInvocationRequest editRequest(HarnessCatalog catalog, String callId) {
    ToolContribution edit = catalog.findTool("edit").orElseThrow();
    return new ToolInvocationRequest(
        new ToolCall(
            callId,
            "edit",
            "{\"path\":\"/workspace/a.txt\",\"old_string\":\"old\",\"new_string\":\"new\"}"),
        new ToolBinding(
            edit.definition(),
            new ContributorBinding(
                edit.id().contributorId().value(), edit.id().localName(), List.of()),
            EnvironmentSupport.ROUTED,
            ToolGatewayTestSupport.ENV_A,
            "test-env"));
  }

  private static ToolApproval approved(ToolEditReview review) {
    Instant now = ToolGatewayTestSupport.TEST_CLOCK.instant();
    return ToolApproval.request(now, "review required", review)
        .decide(
            ToolApprovalDecision.ALLOWED,
            UUID.randomUUID(),
            "actor",
            "approved",
            now.plusSeconds(1));
  }
}
