package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentEditablePropertiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.mcp.McpServerService;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelService;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillCatalogService.PreparedGitImport;
import fun.fengwk.kkstudio.platform.environment.Environment;
import fun.fengwk.kkstudio.platform.environment.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsService;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSectionsDTO;
import fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsUpdateDTO;

import java.util.List;
import java.util.Map;

/**
 * 在一个数据库事务内落地 {@link ConfigSyncPlan}。
 *
 * <p>Git 归档已在事务外准备，技能包发布加入本事务。写入顺序遵守外键与引用依赖：Provider → Model → Skill Package → MCP Server →
 * Environment → Agent → Plugin → Settings。新增 Agent 先以空 subagent allowlist
 * 创建，再在同一事务内统一更新为完整列表，使循环引用可解且事务外不可见中间态。任何写入失败都随异常整体回滚，绝不 捕获后继续。
 *
 * <p>插件复用唯一写入口 {@link PluginConfigurationService}：每项都比较该插件计划期的配置版本；任一项冲突或失败都让整份导入回滚，提交后才在本节点应用。
 *
 * <p>计划在写事务外形成，因此事务内先按同一份插件声明重新核验依赖的 Agent 仍然存在（行锁读取，锁持有到外层事务结束），避免提交悬空依赖。
 */
@AllArgsConstructor
@Component
public class ConfigSyncApplier {

  private final ProviderService providerService;
  private final ModelRepository modelRepository;
  private final ModelService modelService;
  private final SkillCatalogService skillCatalogService;
  private final McpServerService mcpServerService;
  private final AgentRepository agentRepository;
  private final AgentService agentService;
  private final EnvironmentRepository environmentRepository;
  private final EnvironmentService environmentService;
  private final SystemSettingsService systemSettingsService;
  private final PluginConfigurationService pluginConfigurationService;
  private final PluginAgentDependencies pluginAgentDependencies;

  /** 事务内写入计划中的全部事实。 */
  @Transactional
  public void apply(ConfigSyncPlan plan, List<PreparedGitImport> preparedSkills) {
    applyProviders(plan.providers());
    applyModels(plan.models());
    for (PreparedGitImport skill : preparedSkills) {
      skillCatalogService.publishPreparedGitImport(skill);
    }
    for (ConfigSyncPlan.McpImport mcp : plan.mcpServers()) {
      mcpServerService.importServer(
          mcp.name(),
          mcp.url(),
          mcp.headers(),
          mcp.enabled(),
          mcp.timeoutMillis(),
          mcp.discoveredTools());
    }
    applyEnvironments(plan.environments());
    applyAgents(plan.agents());
    applyPlugins(plan);
    if (plan.settings() != null) {
      applySettings(plan.settings());
    }
  }

  private void applyProviders(List<ConfigSyncParser.ProviderSpec> providers) {
    for (ConfigSyncParser.ProviderSpec spec : providers) {
      // 导入是恢复文件业务事实：凭据为 null 表示清空而非沿用旧值；同名不同类型在写入时拒绝。
      providerService.importProvider(spec.properties());
    }
  }

  private void applyModels(List<ConfigSyncParser.ModelSpec> models) {
    for (ConfigSyncParser.ModelSpec spec : models) {
      Model existing = modelRepository.getByName(spec.name());
      if (existing == null) {
        modelService.createModel(spec.properties());
      } else {
        modelService.updateModel(spec.name(), toUpdate(spec.properties(), existing.version()));
      }
    }
  }

  /** 文件只描述创建形态的完整模型定义；导入覆盖现有行时补上计划期 CAS 版本。 */
  private static ModelUpdateDTO toUpdate(ModelCreateDTO create, long expectedVersion) {
    return switch (create) {
      case SingleModelCreateDTO single -> {
        SingleModelUpdateDTO update = new SingleModelUpdateDTO();
        update.setName(single.getName());
        update.setDescription(single.getDescription());
        update.setProviderName(single.getProviderName());
        update.setModelId(single.getModelId());
        update.setConfig(single.getConfig());
        update.setExpectedVersion(expectedVersion);
        yield update;
      }
      case ModelGroupCreateDTO group -> {
        ModelGroupUpdateDTO update = new ModelGroupUpdateDTO();
        update.setName(group.getName());
        update.setDescription(group.getDescription());
        update.setGroup(group.getGroup());
        update.setExpectedVersion(expectedVersion);
        yield update;
      }
    };
  }

  private void applyEnvironments(List<ConfigSyncParser.EnvironmentSpec> environments) {
    for (ConfigSyncParser.EnvironmentSpec spec : environments) {
      Environment existing = environmentRepository.getByName(spec.name());
      if (existing == null) {
        environmentService.importEnvironment(
            spec.name(), spec.registrationTokenHash(), spec.installConfig());
      } else {
        environmentService.updateImportedEnvironment(
            EnvironmentId.of(existing.getId()),
            spec.registrationTokenHash(),
            spec.installConfig(),
            existing.getVersion());
      }
    }
  }

  private void applyAgents(List<ConfigSyncParser.AgentSpec> agents) {
    // 阶段一：先以空 subagent allowlist 创建新 Agent，使同事务内的循环引用有目标行可引用。
    for (ConfigSyncParser.AgentSpec spec : agents) {
      if (agentRepository.getByName(spec.name()) == null) {
        AgentCreateDTO create = buildAgentCreate(spec);
        agentService.createAgent(create);
      }
    }
    // 阶段二：把所有 Agent 更新为完整配置，此时全部被引用的 Agent 已存在。
    for (ConfigSyncParser.AgentSpec spec : agents) {
      Agent current = agentRepository.getByName(spec.name());
      AgentUpdateDTO update = buildAgentUpdate(spec);
      update.setExpectedVersion(current.version());
      agentService.updateAgent(spec.name(), update);
    }
  }

  /**
   * 插件按计划顺序串行写入唯一提交入口，各自比较计划期配置版本。
   *
   * <p>导入只承载普通值，秘密补丁恒为空（全部 KEEP），因此绝不复制或恢复任何凭据。
   */
  private void applyPlugins(ConfigSyncPlan plan) {
    // 计划到写入之间删除的 Agent 无法被配置版本 CAS 发现：先按同一声明批量预锁，再开始任何插件写入。
    pluginAgentDependencies.requireDeclaredAgents(
        plan.plugins().stream()
            .map(
                plugin ->
                    new PluginAgentDependencies.Declaration(
                        plugin.pluginId(), plugin.expectedVersion(), plugin.valuesJson()))
            .toList());
    for (ConfigSyncPlan.PluginImport plugin : plan.plugins()) {
      pluginConfigurationService.configure(
          plugin.pluginId(), plugin.expectedVersion(), plugin.valuesJson(), Map.of());
    }
  }

  private void applySettings(ConfigSyncPlan.SettingsUpdate update) {
    SystemSettingsSectionsDTO sections = update.sections();
    SystemSettingsUpdateDTO dto = new SystemSettingsUpdateDTO();
    dto.setTool(sections.getTool());
    dto.setAiRuntime(sections.getAiRuntime());
    dto.setEnvironment(sections.getEnvironment());
    dto.setBackendNetwork(sections.getBackendNetwork());
    dto.setStorageMedia(sections.getStorageMedia());
    dto.setAdvanced(sections.getAdvanced());
    // 使用计划期快照版本做 CAS：计划到 apply 之间 settings 若被并发修改会正常冲突并随事务整体回滚。
    dto.setExpectedVersion(update.expectedVersion());
    systemSettingsService.update(dto);
  }

  private static AgentCreateDTO buildAgentCreate(ConfigSyncParser.AgentSpec spec) {
    AgentCreateDTO create = new AgentCreateDTO();
    copyAgent(spec.properties(), create);
    create.setName(spec.name());
    create.setConfig(withSubagents(spec.properties().getConfig(), List.of()));
    return create;
  }

  private static AgentUpdateDTO buildAgentUpdate(ConfigSyncParser.AgentSpec spec) {
    AgentUpdateDTO update = new AgentUpdateDTO();
    copyAgent(spec.properties(), update);
    update.setConfig(spec.properties().getConfig());
    return update;
  }

  private static void copyAgent(
      AgentEditablePropertiesDTO source, AgentEditablePropertiesDTO target) {
    target.setDescription(source.getDescription());
    target.setSystemPrompt(source.getSystemPrompt());
    target.setExecution(source.getExecution());
    target.setConfig(source.getConfig());
  }

  private static AgentConfigDTO withSubagents(AgentConfigDTO config, List<String> subagents) {
    AgentConfigDTO copy = new AgentConfigDTO();
    copy.setTools(config.getTools());
    copy.setSkills(config.getSkills());
    copy.setSubagents(subagents);
    return copy;
  }
}
