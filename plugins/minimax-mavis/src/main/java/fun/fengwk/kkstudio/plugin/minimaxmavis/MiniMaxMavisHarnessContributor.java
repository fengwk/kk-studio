package fun.fengwk.kkstudio.plugin.minimaxmavis;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessRegistrar;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.plugin.minimaxmavis.tool.MavisToolDefinitions;
import fun.fengwk.kkstudio.plugin.minimaxmavis.tool.MiniMaxMavisTool;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * MiniMax Mavis 的 Harness Contributor：把 15 个能力注册为模型可见工具。
 *
 * <p>本地贡献名是能力的 kebab-case id（canonical 小写点划线标识符），模型可见名是 {@code mavis_<id>}，两者都由 {@link
 * MavisCapability} 派生，因此贡献身份、工具名与 schema 资源只有一处事实源。全部工具声明 {@code EnvironmentSupport.NONE}：它们只访问
 * Plugin 自己的远端能力，不绑定任何 Environment。
 */
public final class MiniMaxMavisHarnessContributor implements HarnessContributor {

  public static final ContributorId ID = new ContributorId(MiniMaxMavisPlugin.PLUGIN_ID);

  public static final String NAME = MiniMaxMavisPlugin.PLUGIN_NAME;

  public static final String VERSION = "1";

  private static final ContributorDescriptor DESCRIPTOR =
      new ContributorDescriptor(ID, NAME, VERSION, Set.of());

  private final Map<MavisCapability, Tool> toolsByCapability;

  public MiniMaxMavisHarnessContributor(Map<MavisCapability, Tool> toolsByCapability) {
    Objects.requireNonNull(toolsByCapability, "toolsByCapability");
    Map<MavisCapability, Tool> indexed = new EnumMap<>(MavisCapability.class);
    for (MavisCapability capability : MavisCapability.values()) {
      Tool tool = toolsByCapability.get(capability);
      if (tool == null) {
        throw new IllegalStateException(
            "missing MiniMax Mavis tool for capability: " + capability.id());
      }
      if (!capability.toolName().equals(tool.descriptor().name())) {
        throw new IllegalStateException(
            "MiniMax Mavis tool name drift for capability: " + capability.id());
      }
      indexed.put(capability, tool);
    }
    this.toolsByCapability = Map.copyOf(indexed);
  }

  @Override
  public ContributorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public void contribute(HarnessRegistrar registrar) {
    Objects.requireNonNull(registrar, "registrar");
    for (MavisCapability capability : MavisCapability.values()) {
      registrar.registerTool(
          capability.id(), toolsByCapability.get(capability), ToolVisibility.SELECTABLE);
    }
  }

  /** 按能力暴露已注册的工具，便于装配与测试断言一一对应。 */
  public Map<MavisCapability, Tool> tools() {
    return toolsByCapability;
  }

  /** 已注册的模型可见工具数；必须始终等于能力总数。 */
  public int toolCount() {
    return toolsByCapability.size();
  }

  /** 组装 15 个工具时的共享依赖。 */
  public record ToolDependencies(
      PluginCredentialStore credentialStore,
      MavisClient client,
      MiniMaxMavisCapabilityCache capabilityCache,
      MiniMaxMavisResourceAccess resourceAccess,
      ExecutorService executor) {

    public ToolDependencies {
      Objects.requireNonNull(credentialStore, "credentialStore");
      Objects.requireNonNull(client, "client");
      Objects.requireNonNull(capabilityCache, "capabilityCache");
      Objects.requireNonNull(resourceAccess, "resourceAccess");
      Objects.requireNonNull(executor, "executor");
    }
  }

  /** 按能力枚举顺序构建全部 15 个工具；schema 或描述非法时在启动期失败。 */
  public static MiniMaxMavisHarnessContributor create(ToolDependencies dependencies) {
    Map<MavisCapability, Tool> tools = new EnumMap<>(MavisCapability.class);
    for (MavisCapability capability : MavisCapability.values()) {
      tools.put(
          capability,
          new MiniMaxMavisTool(
              capability,
              MavisToolDefinitions.load(capability),
              dependencies.credentialStore(),
              dependencies.client(),
              dependencies.capabilityCache(),
              dependencies.resourceAccess(),
              dependencies.executor()));
    }
    return new MiniMaxMavisHarnessContributor(tools);
  }
}
