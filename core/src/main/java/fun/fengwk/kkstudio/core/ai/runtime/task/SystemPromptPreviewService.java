package fun.fengwk.kkstudio.core.ai.runtime.task;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
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
  private final LiveEnvironmentRegistry environmentRegistry;
  private final SubagentConfig subagentConfig;
  private final AgentPromptComposer promptComposer;
  private final Clock clock;

  public SystemPromptPreviewService(
      HarnessRuntime runtime,
      AgentDefinitionRepository agentDefinitionRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      LiveEnvironmentRegistry environmentRegistry,
      SubagentConfig subagentConfig,
      AgentPromptComposer promptComposer,
      Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.subagentConfig = Objects.requireNonNull(subagentConfig, "subagentConfig");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public String preview(UUID threadId) {
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    EntryPath path = snapshot.entryPath();
    BranchSettings settings = path.baseSettings();
    AgentDefinition agent = agentDefinitionRepository.getByName(settings.agentName());
    Instant now = clock.instant();
    CurrentEnvironmentContext environment = resolveCurrentEnvironment(settings.environment(), now);
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
        previewSkills(config.getSkills(), settings.environment()),
        previewSubagents(config.getSubagents(), path));
  }

  private CurrentEnvironmentContext resolveCurrentEnvironment(
      EnvironmentBinding binding, Instant now) {
    if (binding == null) {
      return new CurrentEnvironmentContext(
          null, null, now.atZone(clock.getZone()).toLocalDate(), null);
    }
    LiveEnvironment liveEnvironment =
        environmentRegistry.find(binding.environmentName()).orElse(null);
    DaemonEnvironmentInfo environmentInfo =
        liveEnvironment == null || liveEnvironment.capabilities() == null
            ? null
            : liveEnvironment.capabilities().environment();
    ZoneId zone = environmentInfo == null ? clock.getZone() : ZoneId.of(environmentInfo.timeZone());
    return new CurrentEnvironmentContext(
        binding,
        environmentInfo == null ? null : environmentInfo.operatingSystem(),
        now.atZone(zone).toLocalDate(),
        environmentInfo == null ? null : environmentInfo.note());
  }

  private List<SkillBinding> previewSkills(List<String> skillNames, EnvironmentBinding binding) {
    if (skillNames == null || skillNames.isEmpty() || binding == null) {
      return List.of();
    }
    LiveEnvironment environment = environmentRegistry.find(binding.environmentName()).orElse(null);
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
        bindings.add(new SkillBinding(skill.name(), skill.description(), binding));
      }
    }
    return List.copyOf(bindings);
  }

  private List<SubagentBinding> previewSubagents(List<String> names, EntryPath path) {
    if (names == null || names.isEmpty() || sessionDepth(path) >= subagentConfig.maxDepth()) {
      return List.of();
    }
    List<SubagentBinding> bindings = new ArrayList<>();
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
