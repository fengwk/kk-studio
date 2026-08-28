package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.AppendCustomEntry;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeTool;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolContext;
import fun.fengwk.kkstudio.harness.contributor.api.DeclarativeToolResult;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.BinaryToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.platform.harness.contributor.ContributorBranchViewLoader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 声明式 Tool 的 frozen provenance、intent 校验顺序与 effects 交付测试。 */
class PlatformToolGatewayDeclarativeTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final ContributorId CONTRIBUTOR_ID = new ContributorId("goal");
  private static final AgentToolId AGENT_TOOL_ID = new AgentToolId("test.declarative-tool");
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "declarative_tool",
          "1",
          "declarative tool",
          "declarative_tool",
          new ToolParamsSchema("args", Map.of(), Set.of(), false),
          ToolSideEffect.IDEMPOTENT,
          Duration.ZERO);
  private static final AgentToolDefinition DEFINITION =
      new AgentToolDefinition(
          AGENT_TOOL_ID, DESCRIPTOR, ToolVisibility.SELECTABLE, AgentToolBackend.DECLARATIVE);

  @Test
  void declarativeToolPassesPermissionPreflightWithoutLocalToolRegistration() {
    // 意图：声明式工具在 preflight 阶段只需 catalog 注册即可通过权限预检。
    Fixture fixture =
        fixture(declarativeTool(DeclarativeToolResult.withoutIntents(result(List.of()))));

    ToolGateway.PreflightResult result =
        fixture.gateway.preflight(fixture.execution("write").request());

    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  @Test
  void declarativeCapacityRejectsBeforeExecutionAndCancelReleasesPermit() {
    // 意图：并发上限在 Declarative Tool 执行前拦截并返回 Overloaded，取消后释放 permit。
    Fixture fixture =
        fixture(
            declarativeTool(DeclarativeToolResult.withoutIntents(result(List.of()))),
            new ConcurrencyAdmission(1));

    ToolGateway.Started first =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    ToolGateway.Overloaded second =
        assertInstanceOf(
            ToolGateway.Overloaded.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));

    // tool.execute 尚未运行，第二次 admission 不能穿透到任何副作用。
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
  void validDeclarativeAppendIsValidatedThenDeliveredAsContributorOwnedEffect() {
    // 意图：合法的 Declarative intent 校验通过并原子交付为 ToolEffectBatch。
    CustomEntryPayload payload =
        new CustomEntryPayload("goal", "state", 1, "{\"objective\":\"ship\"}");
    DeclarativeTool tool =
        declarativeTool(
            new DeclarativeToolResult(
                result(List.of(new TextToolContent("ok"))),
                List.of(new AppendCustomEntry(payload))));
    Fixture fixture = fixture(tool);

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    started.handle().activate();
    fixture.executor.runAll();

    ToolGatewayTestSupport.RecordingListener.Event.Succeeded succeeded =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Succeeded.class,
            fixture.listener.events.get(0));
    assertEquals(List.of(payload), succeeded.effects().customEntries());
    assertEquals(ToolGatewayTestSupport.ASSISTANT_ENTRY_ID, fixture.loadedAssistantId.get());
  }

  @Test
  void frozenContributionMismatchIsRejectedBeforeExecution() {
    // 意图：冻结的 ContributorBinding localName 与 catalog 不匹配时拒绝执行。
    Fixture fixture =
        fixture(declarativeTool(DeclarativeToolResult.withoutIntents(result(List.of()))));
    ToolGateway.StartResult result =
        fixture.gateway.start(fixture.execution("other"), fixture.listener);
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals(PlatformToolGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
    assertTrue(fixture.listener.events.isEmpty());
  }

  @Test
  void declarativeDescriptorMutationAfterFreezeIsRejectedBeforeBranchLoadOrExecute() {
    // 意图：声明式工具在 freeze 后 descriptor 发生漂移或为 null，在加载 branch view 或执行前即被拒绝。
    AtomicReference<ToolDescriptor> liveDescriptor = new AtomicReference<>(DESCRIPTOR);
    DeclarativeTool tool =
        new DeclarativeTool() {
          @Override
          public ToolDescriptor descriptor() {
            return liveDescriptor.get();
          }

          @Override
          public List<StateDeclaration> stateAccesses() {
            return List.of(new StateDeclaration("state", StateMode.WRITE));
          }

          @Override
          public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
            throw new AssertionError("execute must not be reached when descriptor drifted");
          }
        };
    Fixture fixture = fixture(tool);

    // 漂移
    liveDescriptor.set(
        new ToolDescriptor(
            "declarative_tool",
            "2",
            "mutated",
            "declarative_tool",
            DESCRIPTOR.inputSchema(),
            DESCRIPTOR.sideEffect(),
            DESCRIPTOR.timeout()));
    ToolGateway.StartResult result =
        fixture.gateway.start(fixture.execution("write"), fixture.listener);
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals(PlatformToolGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
    assertTrue(
        fixture.loadedAssistantId.get() == null,
        "branch view must not be loaded when descriptor drifted");
    assertTrue(fixture.listener.events.isEmpty());

    // 变为 null
    liveDescriptor.set(null);
    ToolGateway.StartResult nullResult =
        fixture.gateway.start(fixture.execution("write"), fixture.listener);
    ToolGateway.Rejected nullRejected = assertInstanceOf(ToolGateway.Rejected.class, nullResult);
    assertEquals(PlatformToolGateway.TOOL_DEFINITION_MISMATCH_KIND, nullRejected.error().kind());
  }

  @Test
  void declarativeFrozenStateAccessMismatchIsRejected() {
    // 意图：冻结的 ContributorStateAccess 声明与 catalog 中的 StateDeclaration 不一致时拒绝。
    Fixture fixture =
        fixture(declarativeTool(DeclarativeToolResult.withoutIntents(result(List.of()))));
    ContributorBinding mismatchedContributor =
        new ContributorBinding(
            "goal",
            "write",
            List.of(new ContributorStateAccess("state", ContributorStateAccessMode.READ)));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "declarative_tool", "{}"),
            new ToolBinding(DEFINITION, mismatchedContributor, null));
    ToolGateway.Execution execution =
        new ToolGateway.Execution(
            ToolGatewayTestSupport.INVOCATION_ID,
            ToolGatewayTestSupport.THREAD_ID,
            ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
            1,
            request);
    ToolGateway.StartResult result = fixture.gateway.start(execution, fixture.listener);
    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, result);
    assertEquals(PlatformToolGateway.TOOL_DEFINITION_MISMATCH_KIND, rejected.error().kind());
  }

  @Test
  void intentTargetingUnregisteredCustomTypeFailsBeforeExternalization() {
    // 意图：声明式 intent 针对未在 catalog 注册的 customType，在外部化前拒绝并触发 CONTRIBUTOR_CONTRACT_VIOLATION。
    CustomEntryPayload unregisteredPayload =
        new CustomEntryPayload("goal", "unregistered.type", 1, "{\"k\":\"v\"}");
    DeclarativeTool tool =
        new DeclarativeTool() {
          @Override
          public ToolDescriptor descriptor() {
            return DESCRIPTOR;
          }

          @Override
          public List<StateDeclaration> stateAccesses() {
            return List.of(new StateDeclaration("state", StateMode.WRITE));
          }

          @Override
          public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
            return new DeclarativeToolResult(
                result(
                    List.of(new BinaryToolContent("application/octet-stream", new byte[] {1, 2}))),
                List.of(new AppendCustomEntry(unregisteredPayload)));
          }
        };
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(CONTRIBUTOR_ID, "Goal", "1", Set.of()),
            registrar -> {
              registrar.registerCustomEntryType("state-type", "state", 0);
              registrar.registerDeclarativeTool(
                  "write", AGENT_TOOL_ID, tool, ToolVisibility.SELECTABLE, 0);
            });
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore resourceStore =
        new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        new PlatformToolGateway(
            catalog,
            assistantEntryId -> rootBranch(),
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            resourceStore,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            executor,
            ToolGatewayTestSupport.BUSY_RETRY_DELAY,
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
            new ToolCall("call-1", "declarative_tool", "{}"),
            new ToolBinding(DEFINITION, binding, null));
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
    executor.runAll();

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Failed.class, listener.events.get(0));
    assertEquals(
        PlatformToolGateway.CONTRIBUTOR_CONTRACT_VIOLATION_KIND, failed.failure().error().kind());
    assertTrue(resourceStore.puts.isEmpty(), "must not perform any ResourceStore write");
  }

  @Test
  void intentTargetingReadOnlyDeclaredStateFailsBeforeExternalization() {
    // 意图：声明式 intent 针对仅声明为 READ 访问的 customType 进行写入，在外部化前拒绝并触发 CONTRIBUTOR_CONTRACT_VIOLATION。
    CustomEntryPayload readOnlyStatePayload =
        new CustomEntryPayload("goal", "state", 1, "{\"k\":\"v\"}");
    DeclarativeTool tool =
        new DeclarativeTool() {
          @Override
          public ToolDescriptor descriptor() {
            return DESCRIPTOR;
          }

          @Override
          public List<StateDeclaration> stateAccesses() {
            return List.of(new StateDeclaration("state", StateMode.READ));
          }

          @Override
          public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
            return new DeclarativeToolResult(
                result(
                    List.of(new BinaryToolContent("application/octet-stream", new byte[] {1, 2}))),
                List.of(new AppendCustomEntry(readOnlyStatePayload)));
          }
        };
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(CONTRIBUTOR_ID, "Goal", "1", Set.of()),
            registrar -> {
              registrar.registerCustomEntryType("state-type", "state", 0);
              registrar.registerDeclarativeTool(
                  "write", AGENT_TOOL_ID, tool, ToolVisibility.SELECTABLE, 0);
            });
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore resourceStore =
        new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        new PlatformToolGateway(
            catalog,
            assistantEntryId -> rootBranch(),
            new ToolGatewayTestSupport.FakeTransport(),
            new PermissionEvaluator(new ObjectMapper(), new BashSurfaceAnalyzer()),
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)),
            resourceStore,
            ToolGatewayTestSupport.WORKDIR,
            ToolGatewayTestSupport.ENVIRONMENT_ROOT,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            executor,
            ToolGatewayTestSupport.BUSY_RETRY_DELAY,
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
            new ToolCall("call-1", "declarative_tool", "{}"),
            new ToolBinding(DEFINITION, binding, null));
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
    executor.runAll();

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Failed.class, listener.events.get(0));
    assertEquals(
        PlatformToolGateway.CONTRIBUTOR_CONTRACT_VIOLATION_KIND, failed.failure().error().kind());
    assertTrue(resourceStore.puts.isEmpty(), "must not perform any ResourceStore write");
  }

  @Test
  void invalidIntentFailsBeforeResultExternalization() {
    // 意图：恶意/非法 intent（如冒用他人 contributorId）在外部化之前校验失败并拒绝。
    CustomEntryPayload forged =
        new CustomEntryPayload("other", "state", 1, "{\"objective\":\"forged\"}");
    DeclarativeTool tool =
        declarativeTool(
            new DeclarativeToolResult(
                result(
                    List.of(new BinaryToolContent("application/octet-stream", new byte[] {1, 2}))),
                List.of(new AppendCustomEntry(forged))));
    Fixture fixture = fixture(tool);

    ToolGateway.Started started =
        assertInstanceOf(
            ToolGateway.Started.class,
            fixture.gateway.start(fixture.execution("write"), fixture.listener));
    started.handle().activate();
    fixture.executor.runAll();

    ToolGatewayTestSupport.RecordingListener.Event.Failed failed =
        assertInstanceOf(
            ToolGatewayTestSupport.RecordingListener.Event.Failed.class,
            fixture.listener.events.get(0));
    assertEquals(
        PlatformToolGateway.CONTRIBUTOR_CONTRACT_VIOLATION_KIND, failed.failure().error().kind());
    assertTrue(
        fixture.resourceStore.puts.isEmpty(), "invalid intents must fail before externalize");
  }

  private static DeclarativeTool declarativeTool(DeclarativeToolResult result) {
    return new DeclarativeTool() {
      @Override
      public ToolDescriptor descriptor() {
        return DESCRIPTOR;
      }

      @Override
      public List<StateDeclaration> stateAccesses() {
        return List.of(new StateDeclaration("state", StateMode.WRITE));
      }

      @Override
      public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
        assertEquals("declarative_tool", call.toolName());
        return result;
      }
    };
  }

  private static ToolResult result(List<ToolContent> contents) {
    return new ToolResult("call-1", contents, false, "{}");
  }

  private static Fixture fixture(DeclarativeTool tool) {
    return fixture(tool, new ConcurrencyAdmission(Integer.MAX_VALUE));
  }

  private static Fixture fixture(DeclarativeTool tool, ConcurrencyAdmission admission) {
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(CONTRIBUTOR_ID, "Goal", "1", Set.of()),
            registrar -> {
              registrar.registerCustomEntryType("state-type", "state", 0);
              registrar.registerDeclarativeTool(
                  "write", AGENT_TOOL_ID, tool, ToolVisibility.SELECTABLE, 0);
            });
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
    AtomicReference<UUID> loadedAssistantId = new AtomicReference<>();
    ContributorBranchViewLoader loader =
        assistantEntryId -> {
          loadedAssistantId.set(assistantEntryId);
          return rootBranch();
        };
    ToolGatewayTestSupport.ManualExecutor executor = new ToolGatewayTestSupport.ManualExecutor();
    ToolGatewayTestSupport.FakeResourceStore resourceStore =
        new ToolGatewayTestSupport.FakeResourceStore();
    PlatformToolGateway gateway =
        new PlatformToolGateway(
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
            ToolGatewayTestSupport.BUSY_RETRY_DELAY,
            ToolGatewayTestSupport.OVERLOAD_RETRY_DELAY,
            Clock.fixed(NOW, ZoneOffset.UTC),
            admission);
    ToolGatewayTestSupport.RecordingListener listener =
        new ToolGatewayTestSupport.RecordingListener();
    listener.store = resourceStore;
    return new Fixture(gateway, executor, resourceStore, listener, loadedAssistantId);
  }

  private static BranchView rootBranch() {
    UUID id = new UUID(0L, 1L);
    Entry root =
        new Entry(
            id,
            id,
            null,
            new RootPayload(
                new BranchSettings(
                    null, "assistant", new ModelSelection("provider", "model", "default"))),
            NOW);
    return new BranchView(new EntryPath(List.of(root)));
  }

  private record Fixture(
      PlatformToolGateway gateway,
      ToolGatewayTestSupport.ManualExecutor executor,
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
              new ToolCall("call-1", "declarative_tool", "{}"),
              new ToolBinding(DEFINITION, contributor, null));
      return new ToolGateway.Execution(
          ToolGatewayTestSupport.INVOCATION_ID,
          ToolGatewayTestSupport.THREAD_ID,
          ToolGatewayTestSupport.ASSISTANT_ENTRY_ID,
          1,
          request);
    }
  }
}
