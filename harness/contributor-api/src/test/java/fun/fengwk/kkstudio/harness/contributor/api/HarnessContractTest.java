package fun.fengwk.kkstudio.harness.contributor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.tool.AgentToolBackend;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Contributor API 各契约类、sealed ToolContribution 结构与验证测试。 */
class HarnessContractTest {

  private static final ContributorId CID = new ContributorId("goal");
  private static final ToolParamsSchema SCHEMA =
      new ToolParamsSchema("Test schema", Map.of(), Set.of(), false);
  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          "test_tool",
          "1.0",
          "A test tool",
          "test_tool",
          SCHEMA,
          ToolSideEffect.READ_ONLY,
          Duration.ofSeconds(30));

  @Test
  void contributionIdValidatesAndCompares() {
    ContributionId c1 = new ContributionId(CID, "create");
    ContributionId c2 = new ContributionId(CID, "create");
    ContributionId c3 = new ContributionId(CID, "delete");
    ContributionId c4 = new ContributionId(new ContributorId("alpha"), "create");

    assertEquals("goal:create", c1.toString());
    assertEquals(c1, c2);
    assertEquals(c1.hashCode(), c2.hashCode());
    assertTrue(c1.compareTo(c3) < 0);
    assertTrue(c1.compareTo(c4) > 0);

    assertThrows(NullPointerException.class, () -> new ContributionId(null, "create"));
    assertThrows(NullPointerException.class, () -> new ContributionId(CID, null));
    assertThrows(IllegalArgumentException.class, () -> new ContributionId(CID, "Create"));
  }

  @Test
  void contributorDescriptorValidatesRequires() {
    ContributorDescriptor desc =
        new ContributorDescriptor(
            CID, "Goal Contributor", "1.0.0", Set.of(new ContributorId("core")));
    assertEquals(CID, desc.id());
    assertEquals("Goal Contributor", desc.name());
    assertEquals("1.0.0", desc.version());
    assertEquals(Set.of(new ContributorId("core")), desc.requires());

    assertThrows(
        NullPointerException.class, () -> new ContributorDescriptor(null, "Name", "1.0", Set.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new ContributorDescriptor(CID, "", "1.0", Set.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new ContributorDescriptor(CID, "Name", "", Set.of()));
    assertThrows(
        NullPointerException.class, () -> new ContributorDescriptor(CID, "Name", "1.0", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContributorDescriptor(CID, "Name", "1.0", Set.of(CID)));
  }

  @Test
  void stateDeclarationValidates() {
    StateDeclaration decl = new StateDeclaration("goal-state", StateMode.WRITE);
    assertEquals("goal-state", decl.customType());
    assertEquals(StateMode.WRITE, decl.mode());

    assertThrows(NullPointerException.class, () -> new StateDeclaration(null, StateMode.READ));
    assertThrows(NullPointerException.class, () -> new StateDeclaration("state", null));
    assertThrows(
        IllegalArgumentException.class, () -> new StateDeclaration("State", StateMode.READ));
  }

  @Test
  void declarativeToolResultValidatesErrorIntents() {
    CustomEntryPayload payload = new CustomEntryPayload("goal", "state", 1, "{}");
    AppendCustomEntry intent = new AppendCustomEntry(payload);
    ToolResult successResult =
        new ToolResult("call-1", List.of(new TextToolContent("done")), false, "{}");
    ToolResult errorResult = ToolResult.error("call-1", "failed");

    DeclarativeToolResult success = new DeclarativeToolResult(successResult, List.of(intent));
    assertEquals(1, success.intents().size());

    DeclarativeToolResult withoutIntents = DeclarativeToolResult.withoutIntents(successResult);
    assertTrue(withoutIntents.intents().isEmpty());

    assertThrows(
        IllegalArgumentException.class,
        () -> new DeclarativeToolResult(errorResult, List.of(intent)));
    assertThrows(NullPointerException.class, () -> new DeclarativeToolResult(null, List.of()));
    assertThrows(NullPointerException.class, () -> new DeclarativeToolResult(successResult, null));
  }

  @Test
  void declarativeToolContextValidates() {
    BranchView view = new BranchView(rootOnlyPath());
    Instant now = Instant.now();
    DeclarativeToolContext context = new DeclarativeToolContext(view, now);
    assertEquals(view, context.branch());
    assertEquals(now, context.executedAt());

    assertThrows(NullPointerException.class, () -> new DeclarativeToolContext(null, now));
    assertThrows(NullPointerException.class, () -> new DeclarativeToolContext(view, null));
  }

  @Test
  void hostToolContributionValidates() {
    ContributionId id = new ContributionId(CID, "local");
    AgentToolDefinition hostDef =
        new AgentToolDefinition(
            new AgentToolId("test.host"),
            DESCRIPTOR,
            ToolVisibility.SELECTABLE,
            AgentToolBackend.HOST);
    AgentToolDefinition declDef =
        new AgentToolDefinition(
            new AgentToolId("test.decl"),
            DESCRIPTOR,
            ToolVisibility.SELECTABLE,
            AgentToolBackend.DECLARATIVE);
    Tool dummyTool = dummyTool(DESCRIPTOR);

    HostToolContribution contrib = new HostToolContribution(id, hostDef, dummyTool, 5);
    assertEquals(id, contrib.id());
    assertEquals(hostDef, contrib.definition());
    assertEquals(dummyTool, contrib.tool());
    assertEquals(5, contrib.priority());

    assertThrows(
        IllegalArgumentException.class, () -> new HostToolContribution(id, declDef, dummyTool, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HostToolContribution(
                id,
                hostDef,
                dummyTool(
                    new ToolDescriptor(
                        "other",
                        "1.0",
                        "diff",
                        "other",
                        SCHEMA,
                        ToolSideEffect.READ_ONLY,
                        Duration.ZERO)),
                0));
  }

  @Test
  void declarativeToolContributionValidates() {
    ContributionId id = new ContributionId(CID, "local");
    AgentToolDefinition declDef =
        new AgentToolDefinition(
            new AgentToolId("test.decl"),
            DESCRIPTOR,
            ToolVisibility.SELECTABLE,
            AgentToolBackend.DECLARATIVE);
    AgentToolDefinition hostDef =
        new AgentToolDefinition(
            new AgentToolId("test.host"),
            DESCRIPTOR,
            ToolVisibility.SELECTABLE,
            AgentToolBackend.HOST);
    DeclarativeTool dummyDeclarative = dummyDeclarativeTool(DESCRIPTOR);

    DeclarativeToolContribution contrib =
        new DeclarativeToolContribution(id, declDef, dummyDeclarative, List.of(), 3);
    assertEquals(id, contrib.id());
    assertEquals(declDef, contrib.definition());
    assertEquals(dummyDeclarative, contrib.tool());
    assertEquals(3, contrib.priority());

    assertThrows(
        IllegalArgumentException.class,
        () -> new DeclarativeToolContribution(id, hostDef, dummyDeclarative, List.of(), 0));
    assertThrows(
        NullPointerException.class,
        () ->
            new DeclarativeToolContribution(
                id,
                declDef,
                dummyDeclarative,
                Arrays.asList(new StateDeclaration("state", StateMode.READ), null),
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DeclarativeToolContribution(
                id,
                declDef,
                dummyDeclarative,
                List.of(
                    new StateDeclaration("state", StateMode.READ),
                    new StateDeclaration("state", StateMode.WRITE)),
                0));
  }

  @Test
  void environmentCapabilityToolContributionValidates() {
    ContributionId id = new ContributionId(CID, "local");
    AgentToolDefinition envDef =
        new AgentToolDefinition(
            new AgentToolId("test.env"),
            DESCRIPTOR,
            ToolVisibility.SELECTABLE,
            AgentToolBackend.ENVIRONMENT_CAPABILITY);
    AgentToolDefinition hostDef =
        new AgentToolDefinition(
            new AgentToolId("test.host"),
            DESCRIPTOR,
            ToolVisibility.SELECTABLE,
            AgentToolBackend.HOST);
    EnvironmentCapabilityDescriptor capDesc =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("fs.read"), "1", SCHEMA, Duration.ofSeconds(30));

    EnvironmentCapabilityToolContribution contrib =
        new EnvironmentCapabilityToolContribution(id, envDef, capDesc, 1);
    assertEquals(id, contrib.id());
    assertEquals(envDef, contrib.definition());
    assertEquals(capDesc, contrib.capability());
    assertEquals(1, contrib.priority());

    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityToolContribution(id, hostDef, capDesc, 0));

    EnvironmentCapabilityDescriptor mismatchCap =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId("fs.read"), "1", SCHEMA, Duration.ofHours(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EnvironmentCapabilityToolContribution(id, envDef, mismatchCap, 0));
  }

  @Test
  void harnessContributorFactoryWorks() {
    ContributorDescriptor desc = new ContributorDescriptor(CID, "Goal", "1.0", Set.of());
    AtomicBoolean contributed = new AtomicBoolean(false);
    HarnessContributor contributor =
        HarnessContributor.of(desc, registrar -> contributed.set(true));

    assertEquals(desc, contributor.descriptor());
    assertFalse(contributed.get());
  }

  private static Tool dummyTool(ToolDescriptor descriptor) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        return null;
      }
    };
  }

  private static DeclarativeTool dummyDeclarativeTool(ToolDescriptor descriptor) {
    return new DeclarativeTool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public DeclarativeToolResult execute(DeclarativeToolContext context, ToolCall call) {
        return DeclarativeToolResult.withoutIntents(
            new ToolResult("call-1", List.of(new TextToolContent("ok")), false, "{}"));
      }
    };
  }

  private static EntryPath rootOnlyPath() {
    UUID rootId = UUID.randomUUID();
    BranchSettings settings =
        new BranchSettings(null, "test", new ModelSelection("openai", "gpt-4", "default"));
    return new EntryPath(
        List.of(
            new Entry(
                rootId, UUID.randomUUID(), null, new RootPayload(settings, null), Instant.now())));
  }
}
