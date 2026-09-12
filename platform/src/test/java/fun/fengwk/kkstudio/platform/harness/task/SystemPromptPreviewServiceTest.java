package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfig;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 只读预览按最新 Agent 正文与始终存在的 current_environment 组合。 */
class SystemPromptPreviewServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
  private static final UUID THREAD_ID = new UUID(0L, 1L);
  private static final UUID SESSION_ID = new UUID(0L, 2L);

  @Test
  void composesLatestAgentBodyAndCurrentEnvironmentWhenAgentExists() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setToolIds(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    String preview = service(runtime, agents, codec).preview(THREAD_ID);

    assertTrue(preview.startsWith("You are the planner."), preview);
    assertTrue(preview.contains("<current_environment>"), preview);
    assertFalse(preview.contains("- name:"), preview);
    assertTrue(preview.contains("- date: 2026-08-17"), preview);
    assertFalse(preview.contains("<available_skills>"), preview);
  }

  @Test
  void stillRendersCurrentEnvironmentWhenAgentIsMissing() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    when(agents.getByName("assistant")).thenReturn(null);

    String preview = service(runtime, agents, codec).preview(THREAD_ID);

    assertTrue(preview.startsWith("<current_environment>"), preview);
    assertFalse(preview.contains("- name:"), preview);
    assertTrue(preview.contains("- date: 2026-08-17"), preview);
    assertFalse(preview.contains("You are the planner."), preview);
  }

  /** PreviewService 必须把当前 branch 绑定与 live daemon 元数据直接投影到只读提示词。 */
  @Test
  void rendersBoundEnvironmentConnectionMetadata() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentId environmentId = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setEnvironmentId(environmentId.value());
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setToolIds(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);
    EnvironmentConnection liveEnvironment = mock(EnvironmentConnection.class);
    when(liveEnvironment.daemonCapabilities())
        .thenReturn(
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.WSL,
                    "America/Los_Angeles",
                    "Local <dev> & tools.",
                    "/home/dev"),
                List.of()));
    when(environments.find(environmentId)).thenReturn(Optional.of(liveEnvironment));

    String preview = service(runtime, agents, codec, environments).preview(THREAD_ID);

    assertFalse(preview.contains("workspace"), preview);
    assertTrue(preview.contains("- system: wsl"), preview);
    assertTrue(preview.contains("- date: 2026-08-16"), preview);
    assertTrue(preview.contains("- note: Local &lt;dev&gt; &amp; tools."), preview);
  }

  private static SystemPromptPreviewService service(
      HarnessRuntime runtime, AgentDefinitionRepository agents, AgentDefinitionConfigCodec codec) {
    return service(runtime, agents, codec, mock(EnvironmentRegistry.class));
  }

  private static SystemPromptPreviewService service(
      HarnessRuntime runtime,
      AgentDefinitionRepository agents,
      AgentDefinitionConfigCodec codec,
      EnvironmentRegistry environmentRegistry) {
    SubagentConfig subagentConfig = new SubagentConfig(2, 10, 0, Duration.ZERO, 7);
    return new SystemPromptPreviewServiceFactory(
            agents,
            codec,
            environmentRegistry,
            () -> subagentConfig,
            new AgentPromptComposer(() -> subagentConfig),
            Clock.fixed(NOW, ZoneOffset.UTC))
        .create(runtime);
  }

  private static ThreadSnapshot snapshot() {
    BranchSettings settings =
        new BranchSettings("assistant", new ModelSelection("provider", "model", "default"));
    EntryPath path =
        new EntryPath(
            List.of(new Entry(SESSION_ID, SESSION_ID, null, new RootPayload(settings), NOW)));
    ThreadState thread =
        new ThreadState(
            THREAD_ID, SESSION_ID, SESSION_ID, "0".repeat(64), "thread", false, 1, 0, NOW, NOW);
    return new ThreadSnapshot(thread, path, List.of(), null, List.of(), List.of());
  }
}
