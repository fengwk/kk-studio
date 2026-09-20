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
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO;

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
  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  /** Branch settings 冻结的不可变 Environment name：预览只按它解析环境。 */
  private static final String ENV_NAME = "local";

  private static final BranchSettings BOUND_SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), ENV_NAME);

  private static final BranchSettings UNBOUND_SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "model", "default"), null);

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
    config.setTools(List.of());
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
    EnvironmentId environmentId = ENVIRONMENT_ID;

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
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
                    "dev-user",
                    "/home/dev",
                    "Local <dev> & tools.")));
    when(environments.find(environmentId)).thenReturn(Optional.of(liveEnvironment));

    String preview = service(runtime, agents, codec, environments).preview(THREAD_ID);

    assertFalse(preview.contains("workspace"), preview);
    assertTrue(preview.contains("- system: wsl"), preview);
    assertTrue(preview.contains("- date: 2026-08-16"), preview);
    assertTrue(preview.contains("- note: Local &lt;dev&gt; &amp; tools."), preview);
  }

  /** 测试意图：连接行保留的最近一次 READY 宿主 metadata 是环境块的唯一事实来源。 */
  @Test
  void projectsRetainedReadyHostMetadata() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentId environmentId = ENVIRONMENT_ID;

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    when(agents.getByName("assistant")).thenReturn(null);
    EnvironmentConnection liveEnvironment = mock(EnvironmentConnection.class);
    when(liveEnvironment.daemonCapabilities())
        .thenReturn(
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.LINUX, "UTC", "dev-user", "/home/dev", "Live note")));
    when(environments.find(environmentId)).thenReturn(Optional.of(liveEnvironment));

    String preview = service(runtime, agents, codec, environments).preview(THREAD_ID);

    assertTrue(preview.contains("- system: linux"), preview);
    assertTrue(preview.contains("- note: Live note"), preview);
  }

  /**
   * 测试意图：断线后的 CONNECTING 连接行仍保留最近一次 READY 的宿主 metadata，Prompt 必须继续投影该事实而不清空环境块。
   *
   * <p>这是"断线或重新 CONNECTING 不清空 runtime_info"契约在 Prompt 侧的可观察行为。
   */
  @Test
  void connectingConnectionStillProjectsRetainedHostMetadata() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentId environmentId = ENVIRONMENT_ID;

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    when(agents.getByName("assistant")).thenReturn(null);
    EnvironmentConnection connecting = mock(EnvironmentConnection.class);
    when(connecting.daemonCapabilities())
        .thenReturn(
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.WSL, "UTC", "dev-user", "/home/dev", "Retained note")));
    when(environments.find(environmentId)).thenReturn(Optional.of(connecting));

    String preview = service(runtime, agents, codec, environments).preview(THREAD_ID);

    assertTrue(preview.contains("- system: wsl"), preview);
    assertTrue(preview.contains("- note: Retained note"), preview);
  }

  /** 测试意图：验证 Daemon 离线不影响从 Platform 全局目录预览 Skill。 */
  @Test
  void previewsSkillsFromGlobalCatalogWhenDaemonIsOffline() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    SkillCatalogQueryService skillCatalog = mock(SkillCatalogQueryService.class);
    EnvironmentId environmentId = ENVIRONMENT_ID;

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);

    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of(skillRef("test-package", "dev")));
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    // Daemon 离线
    when(environments.find(environmentId)).thenReturn(Optional.empty());
    when(skillCatalog.getPackage("test-package"))
        .thenReturn(
            skillPackage(
                "test-package", List.of(new SkillManifestEntry("dev", "Dev skill description"))));

    String preview =
        service(runtime, agents, codec, environments, boundRepository(), skillCatalog)
            .preview(THREAD_ID);

    assertTrue(preview.contains("<available_skills>"), preview);
    assertTrue(preview.contains("<name>dev</name>"), preview);
    assertTrue(preview.contains("<description>Dev skill description</description>"), preview);
    assertTrue(
        preview.contains("<path>kkstudio:/skills/test-package/dev/SKILL.md</path>"), preview);
  }

  /** 测试意图：验证预览宽容忽略全局目录中不存在的 Skill。 */
  @Test
  void omitsMissingOrStaleSkillsLenientlyInPreview() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    SkillCatalogQueryService skillCatalog = mock(SkillCatalogQueryService.class);
    EnvironmentId environmentId = ENVIRONMENT_ID;

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);

    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of(skillRef("test-package", "dev"), skillRef("test-package", "missing")));
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    when(environments.find(environmentId)).thenReturn(Optional.empty());
    when(skillCatalog.getPackage("test-package"))
        .thenReturn(
            skillPackage(
                "test-package", List.of(new SkillManifestEntry("dev", "Dev skill description"))));

    String preview =
        service(runtime, agents, codec, environments, boundRepository(), skillCatalog)
            .preview(THREAD_ID);

    assertTrue(preview.contains("<available_skills>"), preview);
    assertTrue(preview.contains("<name>dev</name>"), preview);
    assertFalse(preview.contains("missing"), preview);
  }

  /** 测试意图：从未 READY（连接行不存在或没有保留 metadata）时环境块为空，绝不伪造 OS 或 note。 */
  @Test
  void rendersEmptyEnvironmentWhenNoAcceptedHostMetadataExists() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentId environmentId = ENVIRONMENT_ID;

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);

    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of());
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    when(environments.find(environmentId)).thenReturn(Optional.empty());

    String preview = service(runtime, agents, codec, environments).preview(THREAD_ID);

    assertFalse(preview.contains("- system:"), preview);
    assertFalse(preview.contains("- note:"), preview);
    assertTrue(preview.contains("<current_environment>"), preview);
  }

  /** 测试意图：CONNECTING 但无保留 metadata 的连接行同样渲染空环境块。 */
  @Test
  void rendersEmptyEnvironmentWhenConnectingRowHasNoRetainedMetadata() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    EnvironmentId environmentId = ENVIRONMENT_ID;

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    when(agents.getByName("assistant")).thenReturn(null);
    EnvironmentConnection connecting = mock(EnvironmentConnection.class);
    when(connecting.daemonCapabilities()).thenReturn(null);
    when(environments.find(environmentId)).thenReturn(Optional.of(connecting));

    String preview = service(runtime, agents, codec, environments).preview(THREAD_ID);

    assertTrue(preview.startsWith("<current_environment>"), preview);
    assertFalse(preview.contains("- system:"), preview);
    assertFalse(preview.contains("- note:"), preview);
  }

  /** 测试意图：验证 branch 未选择 Environment 时预览仍渲染空环境块，且绝不因「无环境」失败。 */
  @Test
  void rendersEmptyEnvironmentWhenBranchHasNoEnvironment() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot(UNBOUND_SETTINGS));
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of("read"));
    config.setSkills(List.of());
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    String preview =
        service(runtime, agents, codec, mock(EnvironmentRegistry.class)).preview(THREAD_ID);

    assertTrue(preview.startsWith("You are the planner."), preview);
    assertTrue(preview.contains("<current_environment>"), preview);
    assertFalse(preview.contains("- system:"), preview);
    assertFalse(preview.contains("- note:"), preview);
  }

  /** 测试意图：验证 branch 选择的 Environment name 无法解析时，预览宽容回退为空环境上下文（绝不 500）， 且不读取任何 Agent 侧环境字段。 */
  @Test
  void fallsBackToEmptyEnvironmentWhenBranchNameCannotBeResolved() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    EnvironmentRegistry environments = mock(EnvironmentRegistry.class);
    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot(BOUND_SETTINGS));
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);
    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of(skillRef("test-package", "dev")));
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    // EnvironmentRepository 无法按 name 找到 Environment：预览必须宽容，不做任何 live/runtime 报告查询。
    String preview =
        service(runtime, agents, codec, environments, mock(EnvironmentRepository.class))
            .preview(THREAD_ID);

    assertTrue(preview.startsWith("You are the planner."), preview);
    assertTrue(preview.contains("- date: 2026-08-17"), preview);
    assertFalse(preview.contains("- system:"), preview);
    assertFalse(preview.contains("<available_skills>"), preview);
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

  /** 测试意图：Skill 目录查询异常时只省略 skills 段，环境块与系统提示词仍完整渲染。 */
  @Test
  void omitsSkillsSectionWhenCatalogQueryFails() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    AgentDefinitionConfigCodec codec = mock(AgentDefinitionConfigCodec.class);
    SkillCatalogQueryService skillCatalog = mock(SkillCatalogQueryService.class);

    when(runtime.getThreadSnapshot(THREAD_ID)).thenReturn(snapshot());
    AgentDefinition agent = new AgentDefinition();
    agent.setName("assistant");
    agent.setSystemPrompt("You are the planner.");
    agent.setConfigJson("agent-config");
    when(agents.getByName("assistant")).thenReturn(agent);

    AgentDefinitionConfigDTO config = new AgentDefinitionConfigDTO();
    config.setTools(List.of());
    config.setSkills(List.of(skillRef("test-package", "code_search")));
    config.setSubagents(List.of());
    when(codec.decode("agent-config")).thenReturn(config);

    when(skillCatalog.getPackage("test-package"))
        .thenThrow(new IllegalStateException("catalog down"));

    String preview =
        service(
                runtime,
                agents,
                codec,
                mock(EnvironmentRegistry.class),
                boundRepository(),
                skillCatalog)
            .preview(THREAD_ID);

    assertTrue(preview.startsWith("You are the planner."), preview);
    assertTrue(preview.contains("<current_environment>"), preview);
    assertFalse(preview.contains("<available_skills>"), preview);
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
    config.setTools(List.of());
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
        boundRepository(),
        mock(SkillCatalogQueryService.class));
  }

  private static SystemPromptPreviewService service(
      HarnessRuntime runtime,
      AgentDefinitionRepository agents,
      AgentDefinitionConfigCodec codec,
      EnvironmentRegistry environmentRegistry) {
    return service(
        runtime,
        agents,
        codec,
        environmentRegistry,
        boundRepository(),
        mock(SkillCatalogQueryService.class));
  }

  private static SystemPromptPreviewService service(
      HarnessRuntime runtime,
      AgentDefinitionRepository agents,
      AgentDefinitionConfigCodec codec,
      EnvironmentRegistry environmentRegistry,
      EnvironmentRepository environmentRepository) {
    return service(
        runtime,
        agents,
        codec,
        environmentRegistry,
        environmentRepository,
        mock(SkillCatalogQueryService.class));
  }

  private static SystemPromptPreviewService service(
      HarnessRuntime runtime,
      AgentDefinitionRepository agents,
      AgentDefinitionConfigCodec codec,
      EnvironmentRegistry environmentRegistry,
      EnvironmentRepository environmentRepository,
      SkillCatalogQueryService skillCatalog) {
    SubagentConfig subagentConfig = new SubagentConfig(2, 10, 0, Duration.ZERO, 7);
    return new SystemPromptPreviewServiceFactory(
            agents,
            codec,
            environmentRegistry,
            environmentRepository,
            skillCatalog,
            new AgentPromptComposer(() -> subagentConfig),
            Clock.fixed(NOW, ZoneOffset.UTC))
        .create(runtime);
  }

  private static EnvironmentRepository boundRepository() {
    EnvironmentRepository repository = mock(EnvironmentRepository.class);
    Environment environment = new Environment();
    environment.setId(ENVIRONMENT_ID.value());
    environment.setName(ENV_NAME);
    when(repository.getByName(ENV_NAME)).thenReturn(environment);
    return repository;
  }

  private static SkillRefDTO skillRef(String packageName, String name) {
    SkillRefDTO ref = new SkillRefDTO();
    ref.setPackageName(packageName);
    ref.setName(name);
    return ref;
  }

  private static SkillPackage skillPackage(String packageName, List<SkillManifestEntry> entries) {
    SkillPackage pkg = new SkillPackage();
    pkg.setPackageName(packageName);
    pkg.setSkills(entries);
    return pkg;
  }

  private static ThreadSnapshot snapshot() {
    return snapshot(BOUND_SETTINGS);
  }

  private static ThreadSnapshot snapshot(BranchSettings settings) {
    EntryPath path =
        new EntryPath(
            List.of(new Entry(SESSION_ID, SESSION_ID, null, new RootPayload(settings), NOW)));
    ThreadState thread =
        new ThreadState(
            THREAD_ID, SESSION_ID, SESSION_ID, "0".repeat(64), "thread", false, 1, 0, NOW, NOW);
    return new ThreadSnapshot(thread, path, List.of(), null, List.of(), List.of());
  }
}
