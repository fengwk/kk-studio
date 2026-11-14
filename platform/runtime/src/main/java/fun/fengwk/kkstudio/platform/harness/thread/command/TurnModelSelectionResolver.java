package fun.fengwk.kkstudio.platform.harness.thread.command;

import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelGroupMemberExecution;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.model.Model;
import fun.fengwk.kkstudio.platform.catalog.model.ModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.ModelKind;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 逻辑模型（MODEL 或 MODEL_GROUP）到冻结执行身份的解析：选择回退、variant 选择、组成员冻结与能力交集。
 *
 * <p>本类只读 catalog 快照并产出 {@link SelectionSnapshot}，不涉及 Thread 归属、环境或工具面。所有确定性拒绝统一抛 {@link
 * TurnRejection}，由 {@link DatabaseTurnResolver} 在边界投影为 typed {@code Rejected}；SQL
 * 访问异常不在此捕获，仍按类型上抛给调用方处理。
 */
final class TurnModelSelectionResolver {

  private final ModelRepository modelRepository;
  private final ProviderRepository providerRepository;
  private final ModelRuntimeConfigParser modelConfigParser;
  private final ModelGroupConfigCodec groupConfigCodec;
  private final Map<ProviderType, ProviderFactory> providerFactories;

  TurnModelSelectionResolver(
      ModelRepository modelRepository,
      ProviderRepository providerRepository,
      ModelRuntimeConfigParser modelConfigParser,
      ModelGroupConfigCodec groupConfigCodec,
      Map<ProviderType, ProviderFactory> providerFactories) {
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.groupConfigCodec = Objects.requireNonNull(groupConfigCodec, "groupConfigCodec");
    this.providerFactories =
        Map.copyOf(Objects.requireNonNull(providerFactories, "providerFactories"));
  }

  /**
   * 解析本 turn 实际调用的逻辑模型（MODEL 或 GROUP）：Thread 设置的模型存在时使用它；已从 catalog 移除时改用当前 Agent 的默认模型；二者都不可用时拒绝本
   * turn。
   *
   * <p>Thread 设置不被改写：默认模型恢复或重新绑定后，下一 turn 立即生效。单 Model 的 variant 优先沿用原设置；目标模型没有该 variant
   * 时，原模型改用自身的默认 variant，回退模型依次取 Agent 声明的 variant 与模型自身的默认 variant。只对模型回退，Agent 与 Environment
   * 失效一律停止。
   */
  SelectionSnapshot resolve(ModelSelection requested, Agent agent) {
    Model model = modelRepository.getByName(requested.modelName());
    if (model != null) {
      return resolveLogical(model, requested.variant(), null);
    }
    ModelSelection defaultSelection = builtinAgentModel(agent);
    String defaultModel = defaultSelection == null ? null : defaultSelection.modelName();
    Model fallback =
        defaultModel == null || defaultModel.equals(requested.modelName())
            ? null
            : modelRepository.getByName(defaultModel);
    if (fallback == null) {
      throw TurnRejection.of(
          "model "
              + requested.modelName()
              + " and the agent default model are both unavailable; bind an available model");
    }
    return resolveLogical(fallback, requested.variant(), defaultSelection.variant());
  }

  /** 按显式选择解析单个 MODEL 或 MODEL_GROUP；GROUP 必须不带 variant。 */
  SelectionSnapshot resolveExplicit(ModelSelection selection) {
    Objects.requireNonNull(selection, "selection");
    Model model =
        require(
            modelRepository.getByName(selection.modelName()),
            "model not found: " + selection.modelName());
    return resolveLogical(model, selection.variant(), null);
  }

  /** 把冻结选择回写为仅含模型身份的显式选择：组不带 variant，单 Model 保留其 variant。 */
  static ModelSelection toSelection(SelectionSnapshot snapshot) {
    return snapshot.group() == null
        ? ModelSelection.model(
            snapshot.descriptor().modelName(), snapshot.members().getFirst().variant().id())
        : ModelSelection.group(snapshot.descriptor().modelName());
  }

  private static ModelSelection builtinAgentModel(Agent agent) {
    ExecutionSelection execution = agent.execution();
    if (execution == null || !BuiltinExecution.DRIVER.equals(execution.driver())) {
      return null;
    }
    try {
      return BuiltinExecution.model(execution);
    } catch (IllegalArgumentException error) {
      throw TurnRejection.of(
          "invalid agent builtin execution: " + agent.name() + ": " + messageOrClass(error));
    }
  }

  /**
   * 单 Model 按偏好依次取第一个由 config 声明的 variant（{@code preferredVariant}、{@code secondaryVariant}、默认
   * variant）；GROUP 没有组级 variant，一次性冻结组版本与有序成员。
   */
  private SelectionSnapshot resolveLogical(
      Model model, String preferredVariant, String secondaryVariant) {
    if (model.kind() == ModelKind.GROUP) {
      if (preferredVariant != null) {
        throw TurnRejection.of("model group must not select a variant: " + model.name());
      }
      return resolveGroup(model);
    }
    ParsedAgentModelConfig parsed = parseModel(model);
    String variantName =
        findVariant(parsed, preferredVariant) != null
            ? preferredVariant
            : findVariant(parsed, secondaryVariant) != null
                ? secondaryVariant
                : parsed.defaultVariant();
    MemberRuntime member = resolveMember(model, variantName);
    return new SelectionSnapshot(
        member.descriptor(), TurnModelBudget.contextWindow(member.parsed()), List.of(member), null);
  }

  /** 冻结组版本、有序成员及其 Provider/Descriptor/Variant：组能力取成员输入模态的交集、上下文取最小值，成员切换绝不重新读取 Catalog。 */
  private SelectionSnapshot resolveGroup(Model groupModel) {
    ModelGroupConfigDTO group = groupConfigCodec.decode(groupModel.groupConfigJson());
    List<MemberRuntime> members = new ArrayList<>(group.getMembers().size());
    List<ModelGroupMemberExecution> executions = new ArrayList<>(group.getMembers().size());
    Set<ModelInputModality> modalities = null;
    boolean tools = true;
    boolean reasoning = true;
    boolean midConversationSystemMessages = true;
    boolean messageLevelEffort = true;
    int contextWindow = Integer.MAX_VALUE;
    for (ModelGroupMemberDTO member : group.getMembers()) {
      Model memberModel =
          require(
              modelRepository.getByName(member.getModelName()),
              "group member model not found: " + member.getModelName());
      if (memberModel.kind() != ModelKind.MODEL) {
        throw TurnRejection.of("group member is not a single model: " + member.getModelName());
      }
      MemberRuntime runtime = resolveMember(memberModel, member.getVariant());
      members.add(runtime);
      executions.add(
          new ModelGroupMemberExecution(
              runtime.providerType(), runtime.descriptor(), runtime.variant()));
      tools &= runtime.descriptor().tools();
      reasoning &= runtime.descriptor().reasoning();
      midConversationSystemMessages &= runtime.descriptor().midConversationSystemMessages();
      messageLevelEffort &= runtime.descriptor().messageLevelEffort();
      if (modalities == null) {
        modalities = new LinkedHashSet<>(runtime.descriptor().inputModalities());
      } else {
        modalities.retainAll(runtime.descriptor().inputModalities());
      }
      contextWindow = Math.min(contextWindow, TurnModelBudget.contextWindow(runtime.parsed()));
    }
    if (modalities == null || modalities.isEmpty()) {
      throw TurnRejection.of(
          "model group members have no common input modality: " + groupModel.name());
    }
    ModelDescriptor first = members.getFirst().descriptor();
    ModelDescriptor groupDescriptor =
        new ModelDescriptor(
            first.providerName(),
            groupModel.name(),
            first.modelId(),
            modalities,
            tools,
            reasoning,
            midConversationSystemMessages,
            messageLevelEffort);
    ModelGroupExecution execution =
        new ModelGroupExecution(
            groupModel.name(),
            groupModel.version(),
            executions,
            new LinkedHashSet<>(group.getFallbackStatusCodes()),
            group.getCooldownSeconds());
    return new SelectionSnapshot(groupDescriptor, contextWindow, members, execution);
  }

  /** 解析单 Model 的执行身份；{@code variantName} 必须由该 Model 的 config 声明。 */
  private MemberRuntime resolveMember(Model model, String variantName) {
    Provider provider =
        require(
            providerRepository.getByName(model.providerName()),
            "provider not found: " + model.providerName());
    ProviderType providerType = provider.providerType();
    if (providerType == null) {
      throw TurnRejection.of("provider type must not be null");
    }
    require(
        providerFactories.get(providerType),
        "provider factory not found for " + provider.name() + " (" + providerType + ")");
    ParsedAgentModelConfig parsed = parseModel(model);
    ModelVariant variant = findVariant(parsed, variantName);
    if (variant == null) {
      throw TurnRejection.of(
          "model variant not found: " + model.name() + " variant=" + variantName);
    }
    ModelDescriptor descriptor =
        new ModelDescriptor(
            model.providerName(),
            model.name(),
            model.modelId(),
            parsed.inputModalities(),
            parsed.tools(),
            parsed.reasoning(),
            parsed.midConversationSystemMessages(),
            parsed.messageLevelEffort());
    return new MemberRuntime(model.name(), provider, providerType, parsed, descriptor, variant);
  }

  private ParsedAgentModelConfig parseModel(Model model) {
    try {
      return modelConfigParser.parse(model.configJson());
    } catch (IllegalArgumentException error) {
      throw TurnRejection.of("invalid model configuration: " + messageOrClass(error));
    }
  }

  private static ModelVariant findVariant(ParsedAgentModelConfig model, String name) {
    return model.variants().stream()
        .filter(variant -> variant.id().equals(name))
        .findFirst()
        .orElse(null);
  }

  private static <T> T require(T value, String message) {
    if (value == null) {
      throw TurnRejection.of(message);
    }
    return value;
  }

  private static String messageOrClass(RuntimeException error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  /** 一个单 Model 的已解析执行身份；模型组的每个成员各有一份。 */
  record MemberRuntime(
      String modelName,
      Provider provider,
      ProviderType providerType,
      ParsedAgentModelConfig parsed,
      ModelDescriptor descriptor,
      ModelVariant variant) {}

  /** 逻辑模型的解析结果：单 Model 只有一个成员且 {@code group} 为 null；模型组按序列出全部成员与冻结的组执行快照。 */
  record SelectionSnapshot(
      ModelDescriptor descriptor,
      int contextWindow,
      List<MemberRuntime> members,
      ModelGroupExecution group) {
    SelectionSnapshot {
      members = List.copyOf(members);
    }
  }
}
