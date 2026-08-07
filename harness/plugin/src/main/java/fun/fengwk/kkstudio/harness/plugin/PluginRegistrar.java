package fun.fengwk.kkstudio.harness.plugin;

import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;

/**
 * 插件贡献的 scoped registrar：只允许声明能力，不暴露任何 store / gateway / transaction / lock。
 *
 * <p>注册参数是插件内的 canonical 小写 dotted/dashed 本地贡献名（localName）；scoped registrar 在注册时构造 owner-qualified
 * {@link ContributionId}。localName 只在所属插件内唯一，不同插件可以使用相同 localName。
 */
public interface PluginRegistrar {

  /**
   * 注册一个 Tool 贡献。{@code localName} 是该贡献在本插件内的稳定标识；工具本身仍以 {@code (name, version)} 全局唯一。factory 的
   * descriptor 在冻结时读取并校验。
   */
  void registerTool(String localName, ToolFactory factory, ToolVisibility visibility);

  /**
   * 注册一个自定义 Entry type 的 ownership。{@code customType} 的 ownership 键是 {@code (pluginId,
   * customType)}： 不同插件可以各自拥有同名 customType，同一插件内不得重复。
   */
  void registerCustomEntryType(String localName, String customType);

  /** 注册一个上下文投影器；投影器身份即其 scoped {@link ContributionId}。 */
  void registerContextProjector(String localName, ContextProjector projector);
}
