package fun.fengwk.kkstudio.platform.pluginhost.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 插件普通配置声明的 Agent 依赖的宿主唯一入口。
 *
 * <p>普通配置保存、Agent 删除、配置同步的依赖图/计划/事务内重检都必须通过同一份 {@link
 * StudioPlugin#requiredAgentNames(PluginConfig)} 声明解析引用的 Agent 名称，绝不按 pluginId 或字段路径猜测；普通值先经插件自身
 * Schema 归一化，只有安装目录中的插件才可解析（权威目录与安装集合本应一致，漂移即失败）。声明解析是纯解析：不访问数据库、不读取秘密、不访问外部资源。
 *
 * <p>插件拒绝自身普通配置时抛出的 {@link IllegalArgumentException} 是已知的输入边界：统一收敛为固定去敏的 {@link
 * AiValidationException}，不串联 cause、不回显配置值。其它异常（含未知运行时错误与致命错误）照常上抛，绝不伪装成校验失败。
 *
 * <p>锁序固定为 Agent 行锁 → catalog/credential：需要引用 Agent 的保存先按稳定顺序对声明去重后的 Agent 取 {@code for update}
 * 行锁，删 Agent 先取自身行锁再读权威声明，因此并发两侧最多一方成功，绝不提交悬空引用。
 */
@Service
@AllArgsConstructor
public class PluginAgentDependencies {

  private static final String RESOURCE = "plugin";

  /** 固定去敏文案：插件拒绝该普通配置只说明输入无效，绝不回显配置值。 */
  private static final String INVALID_CONFIGURATION = "plugin configuration is invalid: ";

  /** 固定去敏文案：声明引用的 Agent 不存在。 */
  private static final String MISSING_AGENT = "plugins reference a missing agent";

  private final StudioPluginRegistry registry;
  private final PluginConfigurationRepository configurationRepository;
  private final AgentDefinitionRepository definitionRepository;

  /** 一份待核验的插件普通配置声明：安装身份、配置修订与普通值原文。 */
  public record Declaration(String pluginId, long revision, String valuesJson) {

    public Declaration {
      Objects.requireNonNull(pluginId, "pluginId");
      Objects.requireNonNull(valuesJson, "valuesJson");
    }
  }

  /**
   * 给定普通配置声明的 Agent 名称（按名称升序、去重、不可变）。
   *
   * <p>返回有序集合，使「缺失的第一个声明」在依赖图、计划与写入核验里都是稳定的，异常与跳过原因不随哈希顺序漂移。
   *
   * @throws AiValidationException 普通值不符合插件 Schema，或插件拒绝该配置（已知输入边界）
   * @throws IllegalStateException 插件未安装（目录与安装集合漂移）
   */
  public Set<String> declaredAgentNames(String pluginId, long revision, String valuesJson) {
    StudioPlugin plugin = requireInstalled(pluginId);
    PluginConfig config;
    try {
      config = new PluginConfig(revision, plugin.configurationSchema().resolveValues(valuesJson));
    } catch (IllegalArgumentException error) {
      throw invalidConfiguration(pluginId);
    }
    try {
      return Collections.unmodifiableSortedSet(new TreeSet<>(plugin.requiredAgentNames(config)));
    } catch (IllegalArgumentException error) {
      throw invalidConfiguration(pluginId);
    }
  }

  /**
   * 事务内核验一个插件声明的 Agent 依赖仍存在：按稳定顺序对去重后的 Agent 取行锁，锁一直持有到事务结束。
   *
   * @throws AiValidationException 声明无效或引用的 Agent 不存在（固定去敏 400）
   */
  public void requireDeclaredAgents(String pluginId, long revision, String valuesJson) {
    requireDeclaredAgents(List.of(new Declaration(pluginId, revision, valuesJson)));
  }

  /**
   * 事务内批量预锁入口：先解析全部声明，再对合并去重后的 Agent 按稳定顺序逐个加锁，因此开始任何写入前全部引用都已确认存在。
   *
   * @throws AiValidationException 任一声明无效或引用的 Agent 不存在（固定去敏 400）
   */
  public void requireDeclaredAgents(List<Declaration> declarations) {
    Collection<String> required = new TreeSet<>();
    for (Declaration declaration : declarations) {
      required.addAll(
          declaredAgentNames(
              declaration.pluginId(), declaration.revision(), declaration.valuesJson()));
    }
    for (String agent : required) {
      if (definitionRepository.getByNameForUpdate(agent) == null) {
        throw new AiValidationException(RESOURCE, MISSING_AGENT);
      }
    }
  }

  /**
   * 权威 desired manifest 与其匹配历史行中声明引用该 Agent 的插件 ID（按插件 ID 升序、去重、不可变）。
   *
   * <p>只读 desired 事实：不读取本节点已应用的目录、不解析秘密、不猜字段。desired 声明引用了该 Agent 就绝不允许删除它。
   *
   * @throws IllegalStateException 权威目录缺失（宿主未正确初始化，绝不视为「无引用」而放行）
   */
  public Set<String> pluginsDeclaringAgent(String agentName) {
    Objects.requireNonNull(agentName, "agentName");
    TreeSet<String> referencing = new TreeSet<>();
    for (PluginConfigurationRow row : configurationRepository.findAll()) {
      if (registry.find(row.pluginId()).isPresent()
          && declaredAgentNames(row.pluginId(), row.revision(), row.valuesJson())
              .contains(agentName)) {
        referencing.add(row.pluginId());
      }
    }
    return Collections.unmodifiableSortedSet(referencing);
  }

  private StudioPlugin requireInstalled(String pluginId) {
    return registry
        .find(pluginId)
        .orElseThrow(() -> new IllegalStateException("plugin is not installed: " + pluginId));
  }

  private static AiValidationException invalidConfiguration(String pluginId) {
    return new AiValidationException(RESOURCE, INVALID_CONFIGURATION + pluginId);
  }
}
