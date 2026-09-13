package fun.fengwk.kkstudio.platform.harness.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
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
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillInventoryQueryService;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentSkillRefDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInventoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillDTO;

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
  private static final String SOURCE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

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
                    "America/New_York",
                    "Local <dev> & tools.",
                    "/workspace"),
                1,
                List.of()));
    when(environments.find(environmentId)).thenReturn(Optional.of(liveEnvironment));

    String preview =
        service(
                runtime,
                agents,
                codec,
                environments,
                mock(EnvironmentSkillInventoryQueryService.class))
            .preview(THREAD_ID);

    assertFalse(preview.contains("workspace"), preview);
    assertTrue(preview.contains("- system: wsl"), preview);
    assertTrue(preview.contains("- date: 2026-08-16"), preview);
    assertTrue(preview.contains("- note: Local &lt;dev&gt; &amp; tools."), preview);
  }

  /** 测试意图：验证即使 Daemon 处于离线状态，PreviewService 仍然能够通过持久可用 inventory 正常解析出技能提示词。 */
  @Test
  void previewsSkillsFromDurableInventoryWhenDaemonIsOffline() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentSkillInventoryQueryService skillSources =
        mock(EnvironmentSkillInventoryQueryService.class);
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
    config.setSkills(List.of(new AgentSkillRefDTO(SOURCE_ID, "dev")));
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    // Daemon 离线
    when(environments.find(environmentId)).thenReturn(Optional.empty());

    // 持久 inventory 中有此技能
    EnvironmentSkillDTO skillDto = new EnvironmentSkillDTO();
    skillDto.setSourceId(SOURCE_ID);
    skillDto.setName("dev");
    skillDto.setDescription("Dev skill description");
    skillDto.setBaseDirectory("/home/dev/skills/dev");
    skillDto.setContentRevision("0".repeat(64));
    when(skillSources.listUsableSkills(environmentId)).thenReturn(List.of(skillDto));

    String preview = service(runtime, agents, codec, environments, skillSources).preview(THREAD_ID);

    assertTrue(preview.contains("<available_skills>"), preview);
    assertTrue(preview.contains("<name>dev</name>"), preview);
    assertTrue(preview.contains("<description>Dev skill description</description>"), preview);
  }

  /** 测试意图：验证在预览时如果配置的技能在持久 inventory 中不存在或不可用，采取宽容忽略策略（只渲染有效技能，不导致预览失败）。 */
  @Test
  void omitsMissingOrStaleSkillsLenientlyInPreview() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentSkillInventoryQueryService skillSources =
        mock(EnvironmentSkillInventoryQueryService.class);
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
    config.setSkills(
        List.of(
            new AgentSkillRefDTO(SOURCE_ID, "dev"), new AgentSkillRefDTO(SOURCE_ID, "missing")));
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    when(environments.find(environmentId)).thenReturn(Optional.empty());

    EnvironmentSkillDTO skillDto = new EnvironmentSkillDTO();
    skillDto.setSourceId(SOURCE_ID);
    skillDto.setName("dev");
    skillDto.setDescription("Dev skill description");
    skillDto.setBaseDirectory("/home/dev/skills/dev");
    skillDto.setContentRevision("0".repeat(64));
    when(skillSources.listUsableSkills(environmentId)).thenReturn(List.of(skillDto));

    String preview = service(runtime, agents, codec, environments, skillSources).preview(THREAD_ID);

    assertTrue(preview.contains("<available_skills>"), preview);
    assertTrue(preview.contains("<name>dev</name>"), preview);
    assertFalse(preview.contains("missing"), preview);
  }

  /** 测试意图：验证 Daemon 离线时，系统提示词的当前环境块回退到持久 inventory 的元数据（OS、时区、note）。 */
  @Test
  void fallsBackToPersistedEnvironmentMetadataWhenDaemonIsOffline() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentSkillInventoryQueryService skillSources =
        mock(EnvironmentSkillInventoryQueryService.class);
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

    when(environments.find(environmentId)).thenReturn(Optional.empty());

    EnvironmentInventoryDTO inventory = new EnvironmentInventoryDTO();
    inventory.setOperatingSystem("linux");
    inventory.setTimeZone("UTC");
    inventory.setNote("Persisted fallback note");
    when(skillSources.getInventory(environmentId)).thenReturn(inventory);

    String preview = service(runtime, agents, codec, environments, skillSources).preview(THREAD_ID);

    assertTrue(preview.contains("- system: linux"), preview);
    assertTrue(preview.contains("- note: Persisted fallback note"), preview);
  }

  /** 测试意图：验证当持久 inventory 的 note 为 null 时，不阻碍有效持久 OS 的正常渲染。 */
  @Test
  void rendersPersistedOsEvenWhenPersistedNoteIsNull() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentSkillInventoryQueryService skillSources =
        mock(EnvironmentSkillInventoryQueryService.class);
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

    when(environments.find(environmentId)).thenReturn(Optional.empty());

    EnvironmentInventoryDTO inventory = new EnvironmentInventoryDTO();
    inventory.setOperatingSystem("wsl");
    inventory.setTimeZone("UTC");
    inventory.setNote(null);
    when(skillSources.getInventory(environmentId)).thenReturn(inventory);

    String preview = service(runtime, agents, codec, environments, skillSources).preview(THREAD_ID);

    assertTrue(preview.contains("- system: wsl"), preview);
    assertFalse(preview.contains("- note:"), preview);
  }

  /** 测试意图：验证当 Agent 配置解析异常时，预览仍正常降级渲染系统提示词和当前环境，不中断流程。 */
  @Test
  void fallbackWhenConfigCodecThrowsException() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("broken-json");
    when(agents.getByName("assistant")).thenReturn(agent);
    when(codec.decode("broken-json")).thenThrow(new IllegalArgumentException("invalid json"));

    String preview = service(runtime, agents, codec).preview(THREAD_ID);
    assertTrue(preview.startsWith("You are the planner."), preview);
    assertTrue(preview.contains("<current_environment>"), preview);
  }

  /** 测试意图：验证当环境 inventory 查询抛出 AiResourceNotFoundException 或包含无效操作系统/时区时，预览优雅降级。 */
  @Test
  void handlesMissingInventoryAndInvalidMetadataGracefully() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentSkillInventoryQueryService skillSources =
        mock(EnvironmentSkillInventoryQueryService.class);
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
    config.setSkills(List.of(new AgentSkillRefDTO(SOURCE_ID, "code_search")));
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    when(environments.find(environmentId)).thenReturn(Optional.empty());
    // 第一次：getInventory 抛出 AiResourceNotFoundException
    when(skillSources.getInventory(environmentId))
        .thenThrow(new AiResourceNotFoundException("inventory", "not found"));
    // listUsableSkills 抛出 RuntimeException
    when(skillSources.listUsableSkills(environmentId))
        .thenThrow(new RuntimeException("network error"));

    String preview = service(runtime, agents, codec, environments, skillSources).preview(THREAD_ID);
    assertTrue(preview.contains("You are the planner."), preview);
    assertFalse(preview.contains("<available_skills>"), preview);

    // 第二次：inventory 返回无效的 OS wire value 和无效的时区
    EnvironmentInventoryDTO invalidInv = new EnvironmentInventoryDTO();
    invalidInv.setOperatingSystem("invalid_os");
    invalidInv.setTimeZone("Invalid/Zone");
    invalidInv.setNote("Valid note");
    doReturn(invalidInv).when(skillSources).getInventory(environmentId);

    String preview2 =
        service(runtime, agents, codec, environments, skillSources).preview(THREAD_ID);
    assertFalse(preview2.contains("- system:"), preview2);
    assertTrue(preview2.contains("- note: Valid note"), preview2);
  }

  /** 测试意图：验证当 Agent 配置中声明了 subagents 时，预览能够正确通过 agentDefinitionRepository 渲染 subagents。 */
  @Test
  void previewsSubagentsWhenConfigured() {
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
    config.setSubagents(List.of("coder"));
    when(codec.decode("agent-config")).thenReturn(config);

    AgentDefinition subagent = new AgentDefinition();
    subagent.setName("coder");
    subagent.setDescription("Codes solutions.");
    when(agents.getByName("coder")).thenReturn(subagent);

    String preview = service(runtime, agents, codec).preview(THREAD_ID);
    assertTrue(preview.contains("<available_subagents>"), preview);
    assertTrue(preview.contains("Codes solutions."), preview);
  }

  private static SystemPromptPreviewService service(
      HarnessRuntime runtime, AgentDefinitionRepository agents, AgentDefinitionConfigCodec codec) {
    return service(
        runtime,
        agents,
        codec,
        mock(EnvironmentRegistry.class),
        mock(EnvironmentSkillInventoryQueryService.class));
  }

  private static SystemPromptPreviewService service(
      HarnessRuntime runtime,
      AgentDefinitionRepository agents,
      AgentDefinitionConfigCodec codec,
      EnvironmentRegistry environmentRegistry,
      EnvironmentSkillInventoryQueryService skillSources) {
    SubagentConfig subagentConfig = new SubagentConfig(2, 10, 0, Duration.ZERO, 7);
    return new SystemPromptPreviewServiceFactory(
            agents,
            codec,
            environmentRegistry,
            skillSources,
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
