package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SubagentBinding;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogQueryService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.CurrentSkill;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillInventoryQueryService;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInventoryDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 按当前 root-to-head branch 的最新 Agent / Environment / skills / subagents 现算系统提示词。
 *
 * <p>只读预览：不冻结 ModelInvocation，不校验 Tool catalog；Environment 只按当前 {@link
 * BranchSettings#environmentName()} 解析（name 无法解析时宽容回退为空环境上下文，绝不失败），skills 无法解析时省略该段，保证 environment
 * 块始终可见。
 */
public final class SystemPromptPreviewService {

  private final HarnessRuntime runtime;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final EnvironmentRegistry environmentRegistry;
  private final EnvironmentRepository environmentRepository;
  private final EnvironmentSkillInventoryQueryService skillInventoryQueryService;
  private final SkillCatalogQueryService skillCatalogQueryService;
  private final AgentPromptComposer promptComposer;
  private final Clock clock;

  public SystemPromptPreviewService(
      HarnessRuntime runtime,
      AgentDefinitionRepository agentDefinitionRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      EnvironmentRegistry environmentRegistry,
      EnvironmentRepository environmentRepository,
      EnvironmentSkillInventoryQueryService skillInventoryQueryService,
      SkillCatalogQueryService skillCatalogQueryService,
      AgentPromptComposer promptComposer,
      Clock clock) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.environmentRepository =
        Objects.requireNonNull(environmentRepository, "environmentRepository");
    this.skillInventoryQueryService =
        Objects.requireNonNull(skillInventoryQueryService, "skillInventoryQueryService");
    this.skillCatalogQueryService =
        Objects.requireNonNull(skillCatalogQueryService, "skillCatalogQueryService");
    this.promptComposer = Objects.requireNonNull(promptComposer, "promptComposer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public String preview(UUID threadId) {
    ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
    EntryPath path = snapshot.entryPath();
    BranchSettings settings = path.baseSettings();
    AgentDefinition agent = agentDefinitionRepository.getByName(settings.agentName());
    Instant now = clock.instant();
    EnvironmentId environmentId = resolveEnvironmentId(settings.environmentName());
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
        previewSkills(config.getSkills()),
        previewSubagents(config.getSubagents()));
  }

  /** Environment 只按当前 branch settings 的 name 解析；null 或 name 缺失时宽容回退为空环境。 */
  private EnvironmentId resolveEnvironmentId(String environmentName) {
    if (environmentName == null) {
      return null;
    }
    Environment environment = environmentRepository.getByName(environmentName);
    return environment == null ? null : EnvironmentId.of(environment.getId());
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
    EnvironmentInventoryDTO inventory = null;
    if (environmentInfo == null) {
      try {
        inventory = skillInventoryQueryService.getInventory(environmentId);
      } catch (AiResourceNotFoundException ignored) {
        // 离线且环境/inventory不存在时按预览宽容契约忽略
      }
    }
    DaemonOperatingSystem os = environmentInfo != null ? environmentInfo.operatingSystem() : null;
    String note = environmentInfo != null ? environmentInfo.note() : null;
    if (os == null && inventory != null && inventory.getOperatingSystem() != null) {
      try {
        os = DaemonOperatingSystem.fromWireValue(inventory.getOperatingSystem());
      } catch (IllegalArgumentException ignored) {
        os = null;
      }
    }
    if (note == null && inventory != null) {
      note = inventory.getNote();
    }
    String timeZone =
        environmentInfo != null
            ? environmentInfo.timeZone()
            : (inventory != null ? inventory.getTimeZone() : null);
    ZoneId zone;
    if (timeZone != null) {
      try {
        zone = ZoneId.of(timeZone);
      } catch (Exception ex) {
        zone = clock.getZone();
      }
    } else {
      zone = clock.getZone();
    }
    return new CurrentEnvironmentContext(environmentId, os, now.atZone(zone).toLocalDate(), note);
  }

  /**
   * Skills 只从 Platform 自身的全局目录预览，与 Environment 完全无关：环境解析失败也不影响 skills 段。
   *
   * <p>保持只读预览的宽容契约：目录中不存在的名称被省略，绝不因单个失效名称使整段预览失败。
   */
  private List<SkillBinding> previewSkills(List<String> skillNames) {
    if (skillNames == null || skillNames.isEmpty()) {
      return List.of();
    }
    Map<String, CurrentSkill> catalog;
    try {
      catalog = skillCatalogQueryService.currentSkillsByName();
    } catch (RuntimeException error) {
      return List.of();
    }
    List<SkillBinding> bindings = new ArrayList<>();
    for (String skillName : skillNames) {
      CurrentSkill skill = catalog.get(skillName);
      if (skill != null) {
        bindings.add(
            new SkillBinding(
                skill.getName(),
                skill.getPackageName(),
                skill.getPackageVersion(),
                skill.getContentRevision(),
                skill.getDescription()));
      }
    }
    return List.copyOf(bindings);
  }

  private List<SubagentBinding> previewSubagents(List<String> names) {
    if (names == null || names.isEmpty()) {
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
}
