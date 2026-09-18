package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

/**
 * Contributor 贡献的 scoped registrar：只允许声明能力，不暴露任何 store / gateway / transaction / lock。
 *
 * <p>注册参数是 contributor 内的 canonical 小写 dotted/dashed 本地贡献名（localName）；scoped registrar 在注册时构造
 * owner-qualified {@link ContributionId}。localName 只在所属 contributor 内唯一，不同 contributor 可以使用相同
 * localName。
 */
public interface HarnessRegistrar {

  /**
   * 注册一个 Tool 贡献。{@code localName} 是该贡献在本 contributor 内的稳定标识；descriptor 的 model-visible name
   * 在全局唯一，descriptor 在冻结时读取并校验。
   */
  void registerTool(String localName, Tool tool, ToolVisibility visibility, int priority);

  /** 注册 priority 为 0 的 Tool 贡献。 */
  default void registerTool(String localName, Tool tool, ToolVisibility visibility) {
    registerTool(localName, tool, visibility, 0);
  }

  /**
   * 注册一个自定义 Entry type 的 ownership。{@code customType} 的 ownership 键是 {@code (contributorId,
   * customType)}： 不同 contributor 可以各自拥有同名 customType，同一 contributor 内不得重复。
   */
  void registerCustomEntryType(String localName, String customType, int priority);

  /** 注册 priority 为 0 的 custom entry type ownership。 */
  default void registerCustomEntryType(String localName, String customType) {
    registerCustomEntryType(localName, customType, 0);
  }

  /** 注册一个上下文投影器；投影器身份即其 scoped {@link ContributionId}。 */
  void registerContextProjector(String localName, ContextProjector projector, int priority);

  /** 注册 priority 为 0 的上下文投影器。 */
  default void registerContextProjector(String localName, ContextProjector projector) {
    registerContextProjector(localName, projector, 0);
  }
}
