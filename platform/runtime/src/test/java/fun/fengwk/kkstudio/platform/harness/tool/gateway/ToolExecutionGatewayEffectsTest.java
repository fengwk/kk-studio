package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.GoalSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 统一 Tool SPI 的 branch effects、provenance、WRITE 声明与 owner 注入测试。 */
class ToolExecutionGatewayEffectsTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final ContributorId CONTRIBUTOR_ID = new ContributorId("goal");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "effects_tool",
          "effects tool",
          "effects_tool",
          new InputSchema("args", Map.of(), Set.of(), false),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);
  private static final AgentToolDefinition DEFINITION =
      new AgentToolDefinition(DESCRIPTOR, ToolVisibility.SELECTABLE);

  @Test
  void effectsToolPassesPermissionPreflightWithoutLocalToolRegistration() {
    Fixture fixture = fixture(statefulTool(ToolOutcome.withoutEffects(result(List.of()))));

    ToolGateway.PreflightResult result =
        fixture.gateway.preflight(fixture.execution("write", StateAccess.WRITE).request());

    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void capacityIsBusyBeforeBranchLoadAndRunCloseReleasesPermit() {
    Fixture fixture =
        fixture(
            statefulTool(ToolOutcome.withoutEffects(result(List.of()))),
            new ConcurrencyAdmission(1));

    ToolGateway.Run first =
        assertInstanceOf(
                ToolGateway.Admitted.class,
                fixture.gateway.admit(fixture.execution("write", StateAccess.WRITE)))
            .run();
    fixture.loadedAssistantId.set(null);
    ToolGateway.Busy busy =
        assertInstanceOf(
            ToolGateway.Busy.class,
            fixture.gateway.admit(fixture.execution("write", StateAccess.WRITE)));

    assertNull(fixture.loadedAssistantId.get());
    assertEquals(ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY.get(), busy.retryAfter());
    first.close();
    assertInstanceOf(
            ToolGateway.Admitted.class,
            fixture.gateway.admit(fixture.execution("write", StateAccess.WRITE)))
        .run()
        .close();
  }

  @Test
  void validAppendIsValidatedThenDeliveredWithInjectedContributorOwner() {
    AppendCustomEntry entry = new AppendCustomEntry("state", 1, "{\"objective\":\"ship\"}");
    Fixture fixture =
        fixture(
            statefulTool(
                new ToolOutcome(result(List.of(new TextResultContent("ok"))), List.of(entry))));

    ToolGateway.Completion completion = fixture.run("write", StateAccess.WRITE);

    ToolGateway.Succeeded succeeded = assertInstanceOf(ToolGateway.Succeeded.class, completion);
    assertEquals(
        List.of(new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"ship\"}")),
        succeeded.success().effects().customEntries());
    assertEquals(ToolGatewayTestSupport.ASSISTANT_ENTRY_ID, fixture.loadedAssistantId.get());
  }

  @Test
  void frozenContributionMismatchIsRejectedBeforeExecution() {
    Fixture fixture = fixture(statefulTool(ToolOutcome.withoutEffects(result(List.of()))));

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            fixture.gateway.admit(fixture.execution("other", StateAccess.WRITE)));

    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
  }

  @Test
  void frozenStateAccessMismatchIsRejected() {
    Fixture fixture = fixture(statefulTool(ToolOutcome.withoutEffects(result(List.of()))));

    ToolGateway.Rejected rejected =
        assertInstanceOf(
            ToolGateway.Rejected.class,
            fixture.gateway.admit(fixture.execution("write", StateAccess.READ)));

    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
  }

  /** effects 指向未注册的 customType：契约违反，在资源外部化之前以确定性失败收敛。 */
  @Test
  void intentTargetingUnregisteredCustomTypeFailsBeforeExternalization() {
    AppendCustomEntry unregistered = new AppendCustomEntry("unregistered.type", 1, "{\"k\":\"v\"}");
    Fixture fixture =
        fixture(
            statefulTool(
                new ToolOutcome(
                    result(
                        List.of(
                            new BinaryResultContent(
                                "application/octet-stream", new byte[] {1, 2}))),
                    List.of(unregistered))));

    ToolGateway.Failed failed =
        assertInstanceOf(ToolGateway.Failed.class, fixture.run("write", StateAccess.WRITE));

    assertEquals(ToolExecutionGateway.CONTRIBUTOR_CONTRACT_VIOLATION_KIND, failed.error().kind());
    assertTrue(fixture.stager.puts.isEmpty(), "must not stage any resource");
  }

  /** effects 指向只声明 READ 的状态：缺少 WRITE 声明，同样在外部化之前失败。 */
  @Test
  void intentTargetingReadOnlyDeclaredStateFailsBeforeExternalization() {
    AppendCustomEntry entry = new AppendCustomEntry("state", 1, "{\"k\":\"v\"}");
    Fixture fixture =
        fixture(
            readOnlyTool(
                new ToolOutcome(
                    result(
                        List.of(
                            new BinaryResultContent(
                                "application/octet-stream", new byte[] {1, 2}))),
                    List.of(entry))));

    ToolGateway.Failed failed =
        assertInstanceOf(ToolGateway.Failed.class, fixture.run("write", StateAccess.READ));

    assertEquals(ToolExecutionGateway.CONTRIBUTOR_CONTRACT_VIOLATION_KIND, failed.error().kind());
    assertTrue(fixture.stager.puts.isEmpty(), "must not stage any resource");
  }

  private enum StateAccess {
    READ(ContributorStateAccessMode.READ),
    WRITE(ContributorStateAccessMode.WRITE);

    private final ContributorStateAccessMode mode;

    StateAccess(ContributorStateAccessMode mode) {
      this.mode = mode;
    }
  }

  private static Tool statefulTool(ToolOutcome outcome) {
    return tool(outcome, StateMode.WRITE);
  }

  private static Tool readOnlyTool(ToolOutcome outcome) {
    return tool(outcome, StateMode.READ);
  }

  private static Tool tool(ToolOutcome outcome, StateMode mode) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return DESCRIPTOR;
      }

      @Override
      public ToolRequirements requirements() {
        return new ToolRequirements(
            EnvironmentSupport.NONE, List.of(new StateDeclaration("state", mode)));
      }

      @Override
      public ToolOutcome execute(ToolExecutionRequest request, Consumer<ToolResult> progress) {
        assertEquals("effects_tool", request.descriptor().name());
        return outcome;
      }
    };
  }

  private static ToolResult result(List<ResultContent> contents) {
    return new ToolResult("call-1", contents, false, "{}");
  }

  private static Fixture fixture(Tool tool) {
    return fixture(tool, new ConcurrencyAdmission(Integer.MAX_VALUE));
  }

  private static Fixture fixture(Tool tool, ConcurrencyAdmission admission) {
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(CONTRIBUTOR_ID, "Goal", "1", Set.of()),
            registrar -> {
              registrar.registerCustomEntryType("state-type", "state", 0);
              registrar.registerTool("write", tool, ToolVisibility.SELECTABLE, 0);
            });
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    AtomicReference<UUID> loadedAssistantId = new AtomicReference<>();
    ContributorBranchViewLoader loader =
        (assistantEntryId, contributorId) -> {
          loadedAssistantId.set(assistantEntryId);
          return emptyBranch();
        };
    ToolGatewayTestSupport.FakeResourceStager stager =
        new ToolGatewayTestSupport.FakeResourceStager();
    ToolExecutionGateway gateway =
        new ToolExecutionGateway(
            TestRuntimeToolCatalogSource.of(catalog),
            loader,
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            stager,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
            Clock.fixed(NOW, ZoneOffset.UTC),
            admission);
    return new Fixture(gateway, stager, loadedAssistantId);
  }

  private static BranchView emptyBranch() {
    return new BranchView() {
      @Override
      public List<CustomStateSnapshot> customEntries(String customType) {
        return List.of();
      }

      @Override
      public Optional<CustomStateSnapshot> latestCustomEntry(String customType) {
        return Optional.empty();
      }

      @Override
      public Optional<GoalSnapshot> goal() {
        return Optional.empty();
      }
    };
  }

  private record Fixture(
      ToolExecutionGateway gateway,
      ToolGatewayTestSupport.FakeResourceStager stager,
      AtomicReference<UUID> loadedAssistantId) {

    ToolGateway.Execution execution(String localName, StateAccess access) {
      ContributorBinding contributor =
          new ContributorBinding(
              "goal", localName, List.of(new ContributorStateAccess("state", access.mode)));
      ToolInvocationRequest request =
          new ToolInvocationRequest(
              new ToolCall("call-1", "effects_tool", "{}"),
              new ToolBinding(DEFINITION, contributor, EnvironmentSupport.NONE, null, null));
      return new ToolGateway.Execution(
          ToolGatewayTestSupport.INVOCATION_ID,
          ToolGatewayTestSupport.THREAD_ID,
          ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
          request);
    }

    ToolGateway.Completion run(String localName, StateAccess access) {
      ToolGateway.Run run =
          assertInstanceOf(ToolGateway.Admitted.class, gateway.admit(execution(localName, access)))
              .run();
      try {
        return run.execute(partial -> {});
      } finally {
        run.close();
      }
    }
  }
}
