package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.CustomStateSnapshot;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
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
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;
import fun.fengwk.kkstudio.platform.harness.tool.HarnessToolCatalogAdapter;

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

/** 统一 Tool SPI 的 branch effects、provenance、WRITE 声明与 owner 注入测试。 */
class ToolExecutionGatewayEffectsTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final ContributorId CONTRIBUTOR_ID = new ContributorId("goal");
  private static final AgentToolId AGENT_TOOL_ID = new AgentToolId("test.effects-tool");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "effects_tool",
          "1",
          "effects tool",
          "effects_tool",
          new InputSchema("args", Map.of(), Set.of(), false),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);
  private static final AgentToolDefinition DEFINITION =
      new AgentToolDefinition(AGENT_TOOL_ID, DESCRIPTOR, ToolVisibility.SELECTABLE);

  @Test
  void effectsToolPassesPermissionPreflightWithoutLocalToolRegistration() {
    Fixture fixture = fixture(statefulTool(ToolOutcome.withoutEffects(result(List.of()))));

    ToolGateway.PreflightResult result =
        fixture.gateway.preflight(fixture.execution("write").request());

    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void capacityRejectsBeforeExecutionAndCancelReleasesPermit() {
    Fixture fixture =
        fixture(
            statefulTool(ToolOutcome.withoutEffects(result(List.of()))),
            new ConcurrencyAdmission(1));

    ToolGateway.Started first =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    fixture.loadedAssistantId.set(null);
    ToolGateway.RetryLater second =
        assertInstanceOf(
            ToolGateway.RetryLater.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));

    assertTrue(fixture.listener.events.isEmpty());
    assertTrue(fixture.loadedAssistantId.get() == null);
    first.handle().cancel();
    ToolGateway.Started third =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    third.handle().cancel();
    assertEquals(ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY.get(), second.retryAfter());
  }

  @Test
  void validAppendIsValidatedThenDeliveredWithInjectedContributorOwner() {
    AppendCustomEntry entry = new AppendCustomEntry("state", 1, "{\"objective\":\"ship\"}");
    Tool tool =
        statefulTool(new ToolOutcome(result(List.of(new TextResultContent("ok"))), List.of(entry)));
    Fixture fixture = fixture(tool);

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    started.handle().activate();
    fixture.executor.drain();

    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Succeeded.class,
            fixture.listener.events.get(0));
    CustomEntryPayload expectedPayload =
        new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"ship\"}");
    assertEquals(List.of(expectedPayload), succeeded.effects().customEntries());
    assertEquals(ToolGatewayTestSupport.ASSISTANT_ENTRY_ID, fixture.loadedAssistantId.get());
  }

  @Test
  void frozenContributionMismatchIsRejectedBeforeExecution() {
    Fixture fixture = fixture(statefulTool(ToolOutcome.withoutEffects(result(List.of()))));
    ToolGateway.StartResult result =
        fixture.gateway.start(fixture.execution("other"), fixture.listener);
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
    assertTrue(fixture.listener.events.isEmpty());
  }

  @Test
  void descriptorMutationAfterFreezeIsRejectedBeforeBranchLoadOrExecute() {
    AtomicReference<ToolDescriptor> liveDescriptor = new AtomicReference<>(DESCRIPTOR);
    Tool tool =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return liveDescriptor.get();
          }

          @Override
          public ToolRequirements requirements() {
            return new ToolRequirements(
                false, List.of(new StateDeclaration("state", StateMode.WRITE)));
          }

          @Override
          public ToolExecutionHandle execute(
              ToolExecutionRequest request, ToolExecutionListener listener) {
            throw new AssertionError("execute must not be reached when descriptor drifted");
          }
        };
    Fixture fixture = fixture(tool);

    liveDescriptor.set(
        new ToolDescriptor(
            "effects_tool",
            "2",
            "mutated",
            "effects_tool",
            DESCRIPTOR.inputSchema(),
            DESCRIPTOR.sideEffect(),
            DESCRIPTOR.timeout()));
    ToolGateway.StartResult result =
        fixture.gateway.start(fixture.execution("write"), fixture.listener);
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
    assertTrue(
        fixture.loadedAssistantId.get() == null,
        "branch view must not be loaded when descriptor drifted");
    assertTrue(fixture.listener.events.isEmpty());

    liveDescriptor.set(null);
    ToolGateway.StartResult nullResult =
        fixture.gateway.start(fixture.execution("write"), fixture.listener);
    ToolGateway.Rejected nullRejected = assertInstanceOf(ToolGateway.Rejected.class, nullResult);
    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, nullRejected.error().kind());
  }

  @Test
  void frozenStateAccessMismatchIsRejected() {
    Fixture fixture = fixture(statefulTool(ToolOutcome.withoutEffects(result(List.of()))));
    ContributorBinding mismatchedContributor =
        new ContributorBinding(
            "goal",
            "write",
            List.of(new ContributorStateAccess("state", ContributorStateAccessMode.READ)));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "effects_tool", "{}"),
            new ToolBinding(DEFINITION, mismatchedContributor, false, null));
    ToolGateway.Execution execution =
        new ToolGateway.Execution(
            ToolGatewayTestSupport.INVOCATION_ID,
            ToolGatewayTestSupport.THREAD_ID,
            ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
            1,
            request);
    ToolGateway.StartResult result = fixture.gateway.start(execution, fixture.listener);
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals(ToolExecutionGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
  }

  @Test
  void intentTargetingUnregisteredCustomTypeFailsBeforeExternalization() {
    AppendCustomEntry unregisteredEntry =
        new AppendCustomEntry("unregistered.type", 1, "{\"k\":\"v\"}");
    Tool tool =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return DESCRIPTOR;
          }

          @Override
          public ToolRequirements requirements() {
            return new ToolRequirements(
                false, List.of(new StateDeclaration("state", StateMode.WRITE)));
          }

          @Override
          public ToolExecutionHandle execute(
              ToolExecutionRequest request, ToolExecutionListener listener) {
            listener.onComplete(
                new ToolOutcome(
                    result(
                        List.of(
                            new BinaryResultContent(
                                "application/octet-stream", new byte[] {1, 2}))),
                    List.of(unregisteredEntry)));
            return CompletedToolExecutionHandle.INSTANCE;
          }
        };
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(CONTRIBUTOR_ID, "Goal", "1", Set.of()),
            registrar -> {
              registrar.registerCustomEntryType("state-type", "state", 0);
              registrar.registerTool("write", AGENT_TOOL_ID, tool, ToolVisibility.SELECTABLE, 0);
            });
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeResourceStore resourceStore =
        new ToolGatewayTestSupport.FakeResourceStore();
    ToolExecutionGateway gateway =
        new ToolExecutionGateway(
            new HarnessToolCatalogAdapter(catalog),
            catalog,
            (assistantEntryId, contributorId) -> emptyBranch(),
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            resourceStore,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            executor,
            ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ConcurrencyAdmission(Integer.MAX_VALUE));
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();

    ContributorBinding binding =
        new ContributorBinding(
            "goal",
            "write",
            List.of(new ContributorStateAccess("state", ContributorStateAccessMode.WRITE)));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "effects_tool", "{}"),
            new ToolBinding(DEFINITION, binding, false, null));
    ToolGateway.Execution exec =
        new ToolGateway.Execution(
            ToolGatewayTestSupport.INVOCATION_ID,
            ToolGatewayTestSupport.THREAD_ID,
            ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
            1,
            request);

    ToolGateway.Started started =
        assertInstanceOf(ToolGateway.Started.class, gateway.start(exec, listener));
    started.handle().activate();
    executor.drain();

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Failed.class, listener.events.get(0));
    assertEquals(
        ToolExecutionGateway.CONTRIBUTOR_CONTRACT_VIOLATION_KIND, failed.failure().error().kind());
    assertTrue(resourceStore.puts.isEmpty(), "must not perform any ResourceStore write");
  }

  @Test
  void intentTargetingReadOnlyDeclaredStateFailsBeforeExternalization() {
    AppendCustomEntry entry = new AppendCustomEntry("state", 1, "{\"k\":\"v\"}");
    Tool tool =
        new Tool() {
          @Override
          public ToolDescriptor descriptor() {
            return DESCRIPTOR;
          }

          @Override
          public ToolRequirements requirements() {
            return new ToolRequirements(
                false, List.of(new StateDeclaration("state", StateMode.READ)));
          }

          @Override
          public ToolExecutionHandle execute(
              ToolExecutionRequest request, ToolExecutionListener listener) {
            listener.onComplete(
                new ToolOutcome(
                    result(
                        List.of(
                            new BinaryResultContent(
                                "application/octet-stream", new byte[] {1, 2}))),
                    List.of(entry)));
            return CompletedToolExecutionHandle.INSTANCE;
          }
        };
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(CONTRIBUTOR_ID, "Goal", "1", Set.of()),
            registrar -> {
              registrar.registerCustomEntryType("state-type", "state", 0);
              registrar.registerTool("write", AGENT_TOOL_ID, tool, ToolVisibility.SELECTABLE, 0);
            });
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeResourceStore resourceStore =
        new ToolGatewayTestSupport.FakeResourceStore();
    ToolExecutionGateway gateway =
        new ToolExecutionGateway(
            new HarnessToolCatalogAdapter(catalog),
            catalog,
            (assistantEntryId, contributorId) -> emptyBranch(),
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            resourceStore,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            executor,
            ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ConcurrencyAdmission(Integer.MAX_VALUE));
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();

    ContributorBinding binding =
        new ContributorBinding(
            "goal",
            "write",
            List.of(new ContributorStateAccess("state", ContributorStateAccessMode.READ)));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "effects_tool", "{}"),
            new ToolBinding(DEFINITION, binding, false, null));
    ToolGateway.Execution exec =
        new ToolGateway.Execution(
            ToolGatewayTestSupport.INVOCATION_ID,
            ToolGatewayTestSupport.THREAD_ID,
            ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
            1,
            request);

    ToolGateway.Started started =
        assertInstanceOf(ToolGateway.Started.class, gateway.start(exec, listener));
    started.handle().activate();
    executor.drain();

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Failed.class, listener.events.get(0));
    assertEquals(
        ToolExecutionGateway.CONTRIBUTOR_CONTRACT_VIOLATION_KIND, failed.failure().error().kind());
    assertTrue(resourceStore.puts.isEmpty(), "must not perform any ResourceStore write");
  }

  private static Tool statefulTool(ToolOutcome outcome) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return DESCRIPTOR;
      }

      @Override
      public ToolRequirements requirements() {
        return new ToolRequirements(false, List.of(new StateDeclaration("state", StateMode.WRITE)));
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        assertEquals("effects_tool", request.descriptor().name());
        listener.onComplete(outcome);
        return CompletedToolExecutionHandle.INSTANCE;
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
              registrar.registerTool("write", AGENT_TOOL_ID, tool, ToolVisibility.SELECTABLE, 0);
            });
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    AtomicReference<UUID> loadedAssistantId = new AtomicReference<>();
    ContributorBranchViewLoader loader =
        (assistantEntryId, contributorId) -> {
          loadedAssistantId.set(assistantEntryId);
          return emptyBranch();
        };
    ToolGatewayTestSupport.DirectQueueExecutor executor =
        new ToolGatewayTestSupport.DirectQueueExecutor();
    ToolGatewayTestSupport.FakeResourceStore resourceStore =
        new ToolGatewayTestSupport.FakeResourceStore();
    ToolExecutionGateway gateway =
        new ToolExecutionGateway(
            new HarnessToolCatalogAdapter(catalog),
            catalog,
            loader,
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            resourceStore,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            executor,
            ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
            Clock.fixed(NOW, ZoneOffset.UTC),
            admission);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    listener.store = resourceStore;
    return new Fixture(gateway, executor, resourceStore, listener, loadedAssistantId);
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
    };
  }

  private record Fixture(
      ToolExecutionGateway gateway,
      ToolGatewayTestSupport.DirectQueueExecutor executor,
      ToolGatewayTestSupport.FakeResourceStore resourceStore,
      ToolGatewayTestSupport.RecordingListener listener,
      AtomicReference<UUID> loadedAssistantId) {

    ToolGateway.Execution execution(String localName) {
      ContributorBinding contributor =
          new ContributorBinding(
              "goal",
              localName,
              List.of(new ContributorStateAccess("state", ContributorStateAccessMode.WRITE)));
      ToolInvocationRequest request =
          new ToolInvocationRequest(
              new ToolCall("call-1", "effects_tool", "{}"),
              new ToolBinding(DEFINITION, contributor, false, null));
      return new ToolGateway.Execution(
          ToolGatewayTestSupport.INVOCATION_ID,
          ToolGatewayTestSupport.THREAD_ID,
          ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
          1,
          request);
    }
  }
}
