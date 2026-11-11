package fun.fengwk.kkstudio.platform.harness.thread.command;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.SubagentBinding;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.api.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.builtin.environment.FileTool;
import fun.fengwk.kkstudio.harness.contributor.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.daemon.DaemonHostInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonReadyMetadata;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptComposer;
import fun.fengwk.kkstudio.platform.harness.task.AgentPromptInput;
import fun.fengwk.kkstudio.platform.harness.task.CurrentEnvironmentContext;
import fun.fengwk.kkstudio.platform.harness.task.SkillPromptEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Builtin 规划共用的 Agent Turn 目录解析：Agent config、Environment、Skills、Subagent bindings、工具 bindings 与
 * system instruction。
 *
 * <p>它只读取当前 catalog/Environment 的同库事实并租用一次 RuntimeToolCatalog；不解析 Model、Provider、cache 或预算。确定性校验失败以
 * {@link AgentTurnContextException} 明确拒绝，基础设施异常照常传播。
 */
@Component
public final class DatabaseAgentTurnContextResolver {

  private final AgentRepository agentRepository;
  private final AgentConfigCodec agentConfigCodec;
  private final RuntimeToolCatalogSource toolCatalogSource;
  private final EnvironmentRegistry environmentRegistry;
  private final EnvironmentRepository environmentRepository;
  private final SkillPackageRepository skillPackageRepository;
  private final AgentPromptComposer promptComposer;
  private final SchemaJsonCodec schemaCodec;

  public DatabaseAgentTurnContextResolver(
      AgentRepository agentRepository,
      AgentConfigCodec agentConfigCodec,
      RuntimeToolCatalogSource toolCatalogSource,
      EnvironmentRegistry environmentRegistry,
      EnvironmentRepository environmentRepository,
      SkillPackageRepository skillPackageRepository,
      AgentPromptComposer promptComposer) {
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.toolCatalogSource = Objects.requireNonNull(toolCatalogSource, "toolCatalogSource");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.schemaCodec = new SchemaJsonCodec();
  }

  /** 在一次目录租用内解析完整的 Agent Turn 上下文。 */
  public AgentTurnContext resolve(
      UUID threadId, UUID rootSessionId, EntryPath path, BranchSettings settings) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(rootSessionId, "rootSessionId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(settings, "settings");

    Agent agent =
        require(
            agentRepository.getByName(settings.agentName()),
            "agent not found: " + settings.agentName());
    AgentConfigDTO agentConfig = decodeAgentConfig(agent);

    // Environment 只由 branch settings 的可空 environmentName 决定。
    Environment selectedEnvironment = resolveEnvironment(settings.environmentName());
    CurrentEnvironmentContext currentEnvironment = currentEnvironment(selectedEnvironment);
    // 路由身份与 prompt 上下文同源于选中的 Environment，绝不各自回退。
    EnvironmentId environmentId = currentEnvironment.environmentId();
    List<LiveTurnPlan.PlannedSkill> skills = resolveSkills(agentConfig.getSkills());
    List<SubagentBinding> subagentBindings = resolveSubagents(agentConfig.getSubagents());

    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquire()) {
      List<String> toolNames = resolveToolNames(lease.catalog(), agentConfig);
      PlannedTools tools =
          resolveTools(lease.catalog(), environmentId, selectedEnvironment, toolNames);
      String systemInstruction =
          systemInstruction(
              agent.systemPrompt(),
              currentEnvironment,
              rootSessionId,
              threadId,
              tools.bindings().stream().map(binding -> binding.descriptor().name()).toList(),
              promptSkills(environmentId, skills),
              subagentBindings);
      return new AgentTurnContext(
          agent,
          agentConfig,
          currentEnvironment,
          skills,
          subagentBindings,
          tools.bindings(),
          tools.candidates(),
          systemInstruction);
    }
  }

  /**
   * Environment 只由当前 branch settings 的可空 name 决定：null 表示未选择环境；非 null 时按全局唯一且不可变的 name 查 Environment
   * 并取内部路由身份，缺失时确定性拒绝规划。
   */
  private Environment resolveEnvironment(String environmentName) {
    if (environmentName == null) {
      return null;
    }
    Environment environment = environmentRepository.getByName(environmentName);
    if (environment == null) {
      throw rejection("environment not found: " + environmentName);
    }
    return environment;
  }

  private AgentConfigDTO decodeAgentConfig(Agent agent) {
    try {
      return agentConfigCodec.decode(agent.configJson());
    } catch (IllegalStateException error) {
      throw rejection("invalid agent configuration: " + messageOrClass(error));
    }
  }

  /**
   * 从最新 Agent 配置派生本 turn 的模型可见工具名；Issue Run 的业务工具由 Agent 配置显式声明。
   *
   * <p>Agent 只能声明 SELECTABLE 工具；Catalog 缺该工具时按通用规则拒绝规划，绝不静默少一个受控工具。
   */
  private List<String> resolveToolNames(RuntimeToolCatalog catalog, AgentConfigDTO config) {
    LinkedHashSet<String> toolNames = new LinkedHashSet<>();
    for (String toolName : config.getTools()) {
      if (toolName == null || toolName.isBlank()) {
        throw rejection("invalid agent tool name: " + toolName);
      }
      ToolContribution contribution = catalog.findTool(toolName).orElse(null);
      if (contribution == null) {
        throw rejection("tool not found: " + toolName);
      }
      if (contribution.definition().visibility() != ToolVisibility.SELECTABLE) {
        throw rejection("internal tool cannot be selected by an Agent: " + toolName);
      }
      if (!toolNames.add(toolName)) {
        throw rejection("duplicate agent tool name: " + toolName);
      }
    }
    if (!config.getSkills().isEmpty()) {
      toolNames.add(FileTool.READ_NAME);
    }
    if (!config.getSubagents().isEmpty()) {
      // task 只由非空 subagents allowlist 声明：递归深度是调用期 gate，绝不动态裁剪工具面。
      toolNames.add(TaskTool.NAME);
    }
    return List.copyOf(toolNames);
  }

  /**
   * 按最新 Agent 配置派生的精确顺序逐一规划候选工具。声明环境需求的工具（REQUIRED）在 branch 未选择环境时从最终模型工具面过滤，
   * 绝不拒绝规划，但候选事实仍保留并携带唯一稳定原因；branch 已选择环境时冻结内部路由身份与环境名。OPTIONAL 工具在已选环境时冻结环境， 未选环境时两者为 null；NONE
   * 工具始终不绑定环境。贡献声明的固定 {@code requiredEnvironmentId} 只在 branch 已选择另一个非 null 环境时确定性拒绝。
   * 缺失能力仍立即拒绝，绝不静默跳过。
   *
   * <p>返回的候选顺序固定为「SENT 在前、FILTERED 在后」，与 {@link LiveTurnPlan.Planned} 的投影契约一致。
   */
  private PlannedTools resolveTools(
      RuntimeToolCatalog catalog,
      EnvironmentId environmentId,
      Environment selectedEnvironment,
      List<String> toolNames) {
    List<ToolBinding> bindings = new ArrayList<>(toolNames.size());
    List<LiveTurnPlan.CandidateTool> sent = new ArrayList<>(toolNames.size());
    List<LiveTurnPlan.CandidateTool> filtered = new ArrayList<>();
    String environmentName = selectedEnvironment == null ? null : selectedEnvironment.getName();
    for (String toolName : toolNames) {
      ToolContribution contribution = catalog.findTool(toolName).orElse(null);
      if (contribution == null) {
        throw rejection("tool not found: " + toolName);
      }
      EnvironmentSupport environmentSupport = contribution.requirements().environmentSupport();
      String inputSchemaJson =
          schemaCodec.encode(contribution.definition().descriptor().inputSchema());
      if (environmentId == null && environmentSupport == EnvironmentSupport.REQUIRED) {
        // 未选择环境时只从最终模型工具面过滤 REQUIRED 工具：候选仍可见，并携带唯一稳定原因。
        filtered.add(
            candidateTool(
                contribution,
                inputSchemaJson,
                LiveTurnPlan.ToolState.FILTERED,
                LiveTurnPlan.FilterReason.ENVIRONMENT_NOT_SELECTED));
        continue;
      }
      List<ContributorStateAccess> stateAccesses =
          contribution.requirements().stateAccesses().stream()
              .map(
                  access ->
                      new ContributorStateAccess(
                          access.customType(),
                          ContributorStateAccessMode.valueOf(access.mode().name())))
              .toList();
      ContributorBinding contributor =
          new ContributorBinding(
              contribution.id().contributorId().value(),
              contribution.id().localName(),
              stateAccesses);
      EnvironmentId boundEnvironmentId =
          environmentSupport == EnvironmentSupport.NONE ? null : environmentId;
      String boundEnvironmentName =
          environmentSupport == EnvironmentSupport.NONE ? null : environmentName;
      bindings.add(
          new ToolBinding(
              contribution.definition(),
              contributor,
              environmentSupport,
              boundEnvironmentId,
              boundEnvironmentName));
      sent.add(candidateTool(contribution, inputSchemaJson, LiveTurnPlan.ToolState.SENT, null));
    }
    List<LiveTurnPlan.CandidateTool> candidates = new ArrayList<>(sent.size() + filtered.size());
    candidates.addAll(sent);
    candidates.addAll(filtered);
    return new PlannedTools(List.copyOf(bindings), List.copyOf(candidates));
  }

  private static LiveTurnPlan.CandidateTool candidateTool(
      ToolContribution contribution,
      String inputSchemaJson,
      LiveTurnPlan.ToolState state,
      LiveTurnPlan.FilterReason filterReason) {
    return new LiveTurnPlan.CandidateTool(
        contribution.definition().descriptor().name(),
        contribution.definition().descriptor().description(),
        inputSchemaJson,
        contribution.requirements().environmentSupport(),
        contribution.id().toString(),
        state,
        filterReason);
  }

  /**
   * Agent skills 只从最新 Agent config 读取，并按全局 Skill 目录解析为冻结事实。
   *
   * <p>通过 {@link SkillPackageRepository#getPackage(String)} 读取 Platform 权威事实，按 (packageName, name)
   * 精确匹配；缺失或名称不存在时确定性拒绝。Skills 与 Environment 无关，未选择环境的 branch 同样可以规划。
   *
   * <p>技能系统提示只包含名称与描述，不依赖 Environment 的安装状态。
   */
  private List<LiveTurnPlan.PlannedSkill> resolveSkills(List<SkillRefDTO> skillRefs) {
    if (skillRefs == null || skillRefs.isEmpty()) {
      return List.of();
    }
    List<LiveTurnPlan.PlannedSkill> skills = new ArrayList<>(skillRefs.size());
    for (SkillRefDTO ref : skillRefs) {
      if (ref == null || ref.getPackageName() == null || ref.getName() == null) {
        throw rejection("invalid agent skill reference");
      }
      SkillPackage pkg = skillPackageRepository.getPackage(ref.getPackageName());
      if (pkg == null) {
        throw rejection(
            "agent skill not found in the global catalog: "
                + ref.getPackageName()
                + "/"
                + ref.getName());
      }
      SkillManifestEntry entry = pkg.findSkill(ref.getName());
      if (entry == null) {
        throw rejection(
            "agent skill not found in the global catalog: "
                + ref.getPackageName()
                + "/"
                + ref.getName());
      }
      String headCommit = pkg.gitSource() == null ? null : pkg.gitSource().headCommit();
      skills.add(
          new LiveTurnPlan.PlannedSkill(
              pkg.packageName(),
              entry.name(),
              entry.description(),
              pkg.packageVersion(),
              headCommit));
    }
    return List.copyOf(skills);
  }

  /** 技能按 Agent 配置顺序进入系统提示，不依赖 Environment 安装状态。 */
  private static List<SkillPromptEntry> promptSkills(
      EnvironmentId environmentId, List<LiveTurnPlan.PlannedSkill> skills) {
    return skills.stream().map(LiveTurnPlan.PlannedSkill::promptEntry).toList();
  }

  /**
   * 当前 Environment 的宿主事实只来自最近一次被接受的 READY payload：连接行从未 READY 时所有可选事实保持 null，绝不伪造或回退到 Platform
   * 主机。未选择 Environment 时上下文不携带任何宿主事实。
   */
  private CurrentEnvironmentContext currentEnvironment(Environment selected) {
    if (selected == null) {
      return CurrentEnvironmentContext.none();
    }
    EnvironmentId environmentId = EnvironmentId.of(selected.getId());
    EnvironmentConnection connection = environmentRegistry.find(environmentId).orElse(null);
    return CurrentEnvironmentContext.selected(
        environmentId, selected.getName(), selected.getNote(), hostFacts(connection));
  }

  private static DaemonHostInfo hostFacts(EnvironmentConnection connection) {
    DaemonReadyMetadata ready = connection == null ? null : connection.readyMetadata();
    return ready == null ? null : ready.hostInfo();
  }

  /**
   * task 只由最新 Agent 的非空 subagents allowlist 绑定：名称与描述在 ModelRequestSpec 中冻结，Tool 执行绝不依据后续 Agent
   * 配置扩权，也不按 Session depth 动态移除声明；递归上限由 {@code task} 调用期的深度 gate 拒绝。
   */
  private List<SubagentBinding> resolveSubagents(List<String> names) {
    if (names.isEmpty()) {
      return List.of();
    }
    List<SubagentBinding> bindings = new ArrayList<>(names.size());
    for (String name : names) {
      Agent subagent = require(agentRepository.getByName(name), "subagent not found: " + name);
      bindings.add(
          new SubagentBinding(
              subagent.name(), subagent.description() == null ? "" : subagent.description()));
    }
    return List.copyOf(bindings);
  }

  /** 本轮请求唯一的系统指令由 Agent 正文、Environment、工具、Skills 与 Subagents 组合，不写入会话消息。 */
  private String systemInstruction(
      String systemPrompt,
      CurrentEnvironmentContext currentEnvironment,
      UUID rootSessionId,
      UUID threadId,
      List<String> toolNames,
      List<SkillPromptEntry> skills,
      List<SubagentBinding> subagentBindings) {
    String instruction =
        promptComposer.compose(
            new AgentPromptInput(
                systemPrompt,
                currentEnvironment,
                rootSessionId,
                threadId,
                toolNames,
                skills,
                subagentBindings));
    if (instruction == null || instruction.isBlank()) {
      throw rejection("system instruction must not be blank");
    }
    return instruction;
  }

  private static <T> T require(T value, String message) {
    if (value == null) {
      throw rejection(message);
    }
    return value;
  }

  private static AgentTurnContextException rejection(String message) {
    return new AgentTurnContextException(message);
  }

  private static String messageOrClass(RuntimeException error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  /** 一次工具规划的全部事实：冻结进 spec 的 SENT bindings 与「SENT 在前、FILTERED 在后」的完整候选投影。 */
  private record PlannedTools(
      List<ToolBinding> bindings, List<LiveTurnPlan.CandidateTool> candidates) {}
}
