package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 按当前 root-to-head branch 的最新 Agent / Environment / skills / subagents 现算系统提示词。
 *
 * <p>只读预览：不冻结 ModelInvocation，不校验 Tool catalog；skills 无法解析时省略该段，保证 environment 块始终可见。
 */
public final class SystemPromptPreviewService {

  private final HarnessRuntime runtime;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final EnvironmentRegistry environmentRegistry;
  private final SubagentConfigProvider configProvider;
  private final AgentPromptComposer promptComposer;
  private final Clock clock;

  public SystemPromptPreviewService(
      HarnessRuntime runtime,
      AgentDefinitionRepository agentDefinitionRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      EnvironmentRegistry environmentRegistry,
      SubagentConfigProvider configProvider,
      AgentPromptComposer promptComposer,
      Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public String preview(UUID threadId) {
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    EntryPath path = snapshot.entryPath();
    BranchSettings settings = path.baseSettings();
    AgentDefinition agent = agentDefinitionRepository.getByName(settings.agentName());
    Instant now = clock.instant();
    EnvironmentId environmentId = resolveEnvironmentId(agent);
    CurrentEnvironmentContext environment = resolveCurrentEnvironment(environmentId, now);
    if (agent == null) {
      return promptComposer.compose(null, environment, List.of(), List.of());
    }
    AgentDefinitionConfigDTO config;
    try {
      config = agentConfigCodec.decode(agent.getConfigJson());
    } catch (RuntimeException ignored) {
      return promptComposer.compose(agent.getSystemPrompt(), environment, List.of(), List.of());
    }
    return promptComposer.compose(
        agent.getSystemPrompt(),
        environment,
        previewSkills(config.getSkills(), environmentId),
        previewSubagents(config.getSubagents(), path));
  }

  /** 环境完全由 Agent definition 决定：branch settings 不再持有目录状态。 */
  private static EnvironmentId resolveEnvironmentId(AgentDefinition agent) {
    if (agent == null || agent.getEnvironmentId() == null) {
      return null;
    }
    return EnvironmentId.of(agent.getEnvironmentId());
  }

  private CurrentEnvironmentContext resolveCurrentEnvironment(
      EnvironmentId environmentId, Instant now) {
    if (environmentId == null) {
      return new CurrentEnvironmentContext(
          null, null, now.atZone(clock.getZone()).toLocalDate(), null);
    }
    EnvironmentConnection liveEnvironment = environmentRegistry.find(environmentId).orElse(null);
    DaemonEnvironmentInfo environmentInfo =
        liveEnvironment == null || liveEnvironment.daemonCapabilities() == null
            ? null
            : liveEnvironment.daemonCapabilities().environment();
    ZoneId zone = environmentInfo == null ? clock.getZone() : ZoneId.of(environmentInfo.timeZone());
    return new CurrentEnvironmentContext(
        environmentId,
        environmentInfo == null ? null : environmentInfo.operatingSystem(),
        now.atZone(zone).toLocalDate(),
        environmentInfo == null ? null : environmentInfo.note());
  }

  private List<SkillBinding> previewSkills(List<String> skillNames, EnvironmentId environmentId) {
    if (skillNames == null || skillNames.isEmpty() || environmentId == null) {
      return List.of();
    }
    EnvironmentConnection environment = environmentRegistry.find(environmentId).orElse(null);
    if (environment == null) {
      return List.of();
    }
    List<SkillBinding> bindings = new ArrayList<>();
    for (String skillName : skillNames) {
      DaemonSkillDescriptor skill =
          environment.skills().stream()
              .filter(candidate -> candidate.name().equals(skillName))
              .findFirst()
              .orElse(null);
      if (skill != null) {
        bindings.add(new SkillBinding(skill.name(), skill.description(), environmentId));
      }
    }
    return List.copyOf(bindings);
  }

  private List<SubagentBinding> previewSubagents(List<String> names, EntryPath path) {
    if (names == null
        || names.isEmpty()
        || sessionDepth(path) >= configProvider.subagentConfig().maxDepth()) {
      return List.of();
    }
    List<SubagentBinding> bindings = new ArrayList<>(names.size());
    for (String name : names) {
      AgentDefinition subagent = agentDefinitionRepository.getByName(name);
      if (subagent != null) {
        bindings.add(
            new SubagentBinding(
                subagent.getName(),
                subagent.getDescription() == null ? "" : subagent.getDescription()));
      }
    }
    return List.copyOf(bindings);
  }

  private static int sessionDepth(EntryPath path) {
    RootPayload root = (RootPayload) path.root().payload();
    return root.subagentContext() == null ? 1 : root.subagentContext().depth();
  }
}
