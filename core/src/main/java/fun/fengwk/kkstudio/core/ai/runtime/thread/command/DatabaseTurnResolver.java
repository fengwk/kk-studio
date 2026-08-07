package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelRuntimeConfigParser.ParsedAgentModelConfig;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.catalog.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.ai.catalog.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheAffinityKeyFactory;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactories;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderFactory;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderType;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 生产 Core 的 {@link TurnResolver}：把 candidate {@link EntryPath} 的最新 branch settings 解析为冻结的 {@link
 * ModelInvocationRequest}。
 *
 * <p>输入事实只有 candidate path 的 {@link BranchSettings}（environmentName / agentName / {@link
 * ModelSelection} / thinkingLevel / ordered activeTools）与调用方冻结的 YOLO 开关；实现只按这些精确引用读取最新 catalog /
 * environment 事实，绝不回读 Chat defaults、绝不 fallback agent/model/variant/thinking/tools，也绝不静默丢弃缺失能力。
 * environmentName 是最新 branch 的不可变逻辑路由：ENVIRONMENT 工具一律按最新 {@code settings.environmentName()} 绑定（可为
 * null 或当前不可用，实际执行时失败）；Agent skills 要求最新 Environment 提供 live descriptors，缺失/未 READY 时确定性拒绝 且绝不回看更旧的
 * branch settings。配置或 Environment 不满足一律返回 {@link Result.Rejected}（稳定 error code {@value
 * #REJECTION_CODE}）；只有 repository / registry 等基础设施异常向上传播，由 ThreadProcessor reschedule。
 */
@Component
public final class DatabaseTurnResolver implements TurnResolver {

  /** 所有确定性拒绝共用的稳定 AssistantError code。 */
  public static final String REJECTION_CODE = "PLANNING_FAILED";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentModelRepository modelRepository;
  private final AgentProviderRepository providerRepository;
  private final AgentDefinitionConfigCodec agentConfigCodec;
  private final AgentModelRuntimeConfigParser modelConfigParser;
  private final ProviderFactories providerFactories;
  private final ToolCatalog toolCatalog;
  private final LiveEnvironmentRegistry environmentRegistry;
  private final EnvironmentGatewayProperties environmentGatewayProperties;
  private final Clock clock;
  private final ProviderMessageProjector messageProjector;
  private final ToolDescriptorJsonCodec toolDescriptorCodec;
  private final PromptCacheAffinityKeyFactory cacheKeyFactory;

  public DatabaseTurnResolver(
      AgentDefinitionRepository agentDefinitionRepository,
      AgentModelRepository modelRepository,
      AgentProviderRepository providerRepository,
      AgentDefinitionConfigCodec agentConfigCodec,
      AgentModelRuntimeConfigParser modelConfigParser,
      ProviderFactories providerFactories,
      ToolCatalog toolCatalog,
      LiveEnvironmentRegistry environmentRegistry,
      EnvironmentGatewayProperties environmentGatewayProperties,
      Clock clock) {
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.agentConfigCodec = Objects.requireNonNull(agentConfigCodec, "agentConfigCodec");
    this.modelConfigParser = Objects.requireNonNull(modelConfigParser, "modelConfigParser");
    this.providerFactories = Objects.requireNonNull(providerFactories, "providerFactories");
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.environmentGatewayProperties =
        Objects.requireNonNull(environmentGatewayProperties, "environmentGatewayProperties");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.messageProjector = new ProviderMessageProjector();
    this.toolDescriptorCodec = new ToolDescriptorJsonCodec();
    this.cacheKeyFactory = new PromptCacheAffinityKeyFactory();
  }

  @Override
  public Result resolve(long threadId, EntryPath path, boolean yoloEnabled) {
    Objects.requireNonNull(path, "path");
    try {
      ModelInvocationRequest request = resolveLive(path, yoloEnabled);
      return new TurnResolver.Resolved(request);
    } catch (Rejection rejection) {
      // 只把显式构造的确定性拒绝转为 typed Rejected；repository/registry 等基础设施异常原样传播。
      return rejected(rejection.getMessage());
    }
  }

  private ModelInvocationRequest resolveLive(EntryPath path, boolean yoloEnabled) {
    BranchSettings settings = path.baseSettings();
    long sessionId = path.root().sessionId();

    AgentDefinition agent =
        require(
            agentDefinitionRepository.getByName(settings.agentName()),
            "agent not found: " + settings.agentName());
    AgentDefinitionConfigDTO agentConfig = decodeAgentConfig(agent);

    ModelSelection selection = settings.model();
    AgentProvider provider =
        require(
            providerRepository.getByName(selection.providerName()),
            "provider not found: " + selection.providerName());
    ProviderType providerType = toProviderType(provider);
    AgentModel model =
        require(
            modelRepository.getByProviderNameAndName(
                selection.providerName(), selection.modelName()),
            "model not found: " + selection.providerName() + "/" + selection.modelName());
    ParsedAgentModelConfig parsedModel = parseModel(model);
    ModelVariant variant = findVariant(parsedModel, selection.variant());
    if (variant == null) {
      throw rejection(
          "model variant not found: "
              + selection.providerName()
              + "/"
              + selection.modelName()
              + " variant="
              + selection.variant());
    }
    ProviderFactory providerFactory =
        require(
            providerFactories.lookup(providerType).orElse(null),
            "provider factory not found for "
                + selection.providerName()
                + " ("
                + providerType
                + ")");

    List<ToolBinding> toolBindings = resolveTools(settings);
    List<SkillBinding> skillBindings = resolveSkills(agentConfig.getSkills(), settings);

    if (!toolBindings.isEmpty() && !parsedModel.tools()) {
      throw rejection(
          "model does not support tools: "
              + selection.providerName()
              + "/"
              + selection.modelName());
    }
    ModelVariant effectiveVariant = withReasoningEffort(variant, settings.thinkingLevel());
    if (effectiveVariant.reasoningEffort() != null && !parsedModel.reasoning()) {
      throw rejection(
          "model does not support reasoning: "
              + selection.providerName()
              + "/"
              + selection.modelName());
    }

    ModelDescriptor descriptor =
        new ModelDescriptor(
            selection.providerName(),
            selection.modelName(),
            parsedModel.tools(),
            parsedModel.reasoning(),
            parsedModel.pricing());
    ProviderRequest providerRequest =
        providerRequest(
            descriptor,
            effectiveVariant,
            agent.getSystemPrompt(),
            skillBindings,
            path,
            toolBindings,
            sessionId,
            cachePolicy(providerFactory));
    return new ModelInvocationRequest(
        settings.environmentName(), providerRequest, toolBindings, skillBindings, yoloEnabled);
  }

  private AgentDefinitionConfigDTO decodeAgentConfig(AgentDefinition agent) {
    try {
      return agentConfigCodec.decode(agent.getConfigJson());
    } catch (IllegalStateException error) {
      throw rejection("invalid agent configuration: " + messageOrClass(error));
    }
  }

  private ParsedAgentModelConfig parseModel(AgentModel model) {
    try {
      return modelConfigParser.parse(model.getConfigJson());
    } catch (IllegalArgumentException error) {
      throw rejection("invalid model configuration: " + messageOrClass(error));
    }
  }

  /**
   * 按 {@link BranchSettings#activeTools()} 的精确顺序逐一绑定。ENVIRONMENT 工具一律绑定最新 branch 的 {@code
   * settings.environmentName()}（可为 null 或当前不可用——实际执行时确定性失败）；PLATFORM 工具不携带路由。缺失能力仍立即拒绝， 绝不静默跳过。
   */
  private List<ToolBinding> resolveTools(BranchSettings settings) {
    List<ToolBinding> bindings = new ArrayList<>(settings.activeTools().size());
    for (String name : settings.activeTools()) {
      Optional<ToolDescriptor> selectable = toolCatalog.findSelectable(name);
      if (selectable.isPresent() && selectable.get().type() == ToolType.PLATFORM) {
        bindings.add(new ToolBinding(selectable.get(), ToolType.PLATFORM, null));
        continue;
      }
      Optional<ToolDescriptor> internal = toolCatalog.findInternal(name);
      if (internal.isPresent()) {
        bindings.add(new ToolBinding(internal.get(), ToolType.PLATFORM, null));
        continue;
      }
      // selectable catalog 合并了 Environment 工具；ENVIRONMENT 工具由固定 catalog 精确提供。
      Optional<ToolDescriptor> environmentTool =
          selectable
              .filter(descriptor -> descriptor.type() == ToolType.ENVIRONMENT)
              .or(() -> EnvironmentToolCatalog.find(name));
      if (environmentTool.isPresent()) {
        bindings.add(
            new ToolBinding(
                environmentTool.get(), ToolType.ENVIRONMENT, settings.environmentName()));
        continue;
      }
      throw rejection("tool not found: " + name);
    }
    return List.copyOf(bindings);
  }

  /**
   * Agent skills 只从 Agent config 读取，且必须由最新选中的 Environment 精确提供（live descriptors）； load_skill
   * 必须显式出现在 activeTools 中。
   */
  private List<SkillBinding> resolveSkills(List<String> skillNames, BranchSettings settings) {
    if (skillNames.isEmpty()) {
      return List.of();
    }
    EnvironmentName environmentName = settings.environmentName();
    if (environmentName == null) {
      throw rejection(
          "agent skills require the latest selected environment but the branch has no environmentName");
    }
    // skills 需要最新 Environment 提供 live descriptors：按最新名称精确查找并要求 READY（同一可用性规则），
    // 缺失/未 READY 确定性拒绝，绝不回看更旧的 branch settings。
    LiveEnvironment environment = environmentRegistry.find(environmentName).orElse(null);
    if (environment == null) {
      throw rejection(
          "agent skills require the latest selected environment which is not live: "
              + environmentName);
    }
    if (!environment.isReady(
        clock.instant(), environmentGatewayProperties.requireHeartbeatTimeout())) {
      throw rejection(
          "agent skills require the latest selected environment which is not ready: "
              + environmentName);
    }
    if (!settings.activeTools().contains(LoadSkillTool.NAME)) {
      throw rejection("agent has skills but activeTools must include " + LoadSkillTool.NAME);
    }
    List<SkillBinding> bindings = new ArrayList<>(skillNames.size());
    for (String skillName : skillNames) {
      DaemonSkillDescriptor skill =
          environment.skills().stream()
              .filter(candidate -> candidate.name().equals(skillName))
              .findFirst()
              .orElse(null);
      if (skill == null) {
        throw rejection(
            "skill not found on the latest environment "
                + settings.environmentName()
                + ": "
                + skillName);
      }
      bindings.add(new SkillBinding(skill.name(), skill.description(), settings.environmentName()));
    }
    return List.copyOf(bindings);
  }

  private static ModelVariant findVariant(ParsedAgentModelConfig model, String name) {
    return model.variants().stream()
        .filter(variant -> variant.id().equals(name))
        .findFirst()
        .orElse(null);
  }

  /** BranchSettings.thinkingLevel 是冻结的 reasoning effort override；其余 variant 字段原样保留。 */
  private static ModelVariant withReasoningEffort(ModelVariant variant, String thinkingLevel) {
    return new ModelVariant(
        variant.id(),
        variant.maxOutputTokens(),
        variant.temperature(),
        variant.topP(),
        variant.topK(),
        variant.frequencyPenalty(),
        variant.presencePenalty(),
        variant.stopSequences(),
        thinkingLevel);
  }

  private static PromptCachePolicy cachePolicy(ProviderFactory providerFactory) {
    PromptCacheCapability capability = providerFactory.promptCacheCapability();
    PromptCacheRetention retention =
        capability.supports(PromptCacheRetention.SHORT)
            ? PromptCacheRetention.SHORT
            : PromptCacheRetention.NONE;
    return PromptCachePolicy.of(capability, retention);
  }

  private ProviderRequest providerRequest(
      ModelDescriptor descriptor,
      ModelVariant variant,
      String systemPrompt,
      List<SkillBinding> skillBindings,
      EntryPath path,
      List<ToolBinding> toolBindings,
      long sessionId,
      PromptCachePolicy cachePolicy) {
    List<AgentMessage> semanticMessages = new ArrayList<>();
    String composedPrompt = composeSystemPrompt(systemPrompt, skillBindings);
    if (!composedPrompt.isBlank()) {
      semanticMessages.add(AgentMessage.system(composedPrompt));
    }
    for (Entry entry : path.entries()) {
      EntryPayload payload = entry.payload();
      if (payload instanceof MessagePayload message) {
        semanticMessages.add(message.message());
      } else if (payload instanceof CustomMessagePayload message) {
        // CUSTOM_MESSAGE 通过冻结的 AgentMessage 保持 model-visible；details 绝不投影。
        semanticMessages.add(message.message());
      } else if (payload instanceof AssistantAbortedPayload message) {
        semanticMessages.add(message.message());
      }
      // CUSTOM 是透明 branch state：provider 消息投影默认忽略它。
    }
    List<ProviderToolDefinition> providerTools = new ArrayList<>(toolBindings.size());
    for (ToolBinding binding : toolBindings) {
      ToolDescriptor tool = binding.descriptor();
      providerTools.add(
          new ProviderToolDefinition(
              tool.name(),
              tool.description(),
              toolDescriptorCodec.encodeInputSchema(tool.inputSchema())));
    }
    ProviderRequest baseRequest =
        new ProviderRequest(
            descriptor,
            variant,
            messageProjector.project(semanticMessages),
            providerTools,
            ProviderCacheControl.none());
    // cache policy 由当前 ProviderFactory 显式解析并传入；不随 descriptor/request 持久化。
    return new PromptCacheRequestFinalizer(sessionId, cacheKeyFactory)
        .apply(baseRequest, cachePolicy);
  }

  private static String composeSystemPrompt(String systemPrompt, List<SkillBinding> skillBindings) {
    if (skillBindings.isEmpty()) {
      return systemPrompt;
    }
    StringBuilder section = new StringBuilder();
    section.append("\n\n");
    section.append("以下技能提供针对特定任务的专业指令。\n");
    section.append("当任务与描述匹配时，从 <available_skills> 中按其准确名称使用技能。\n");
    section.append("\n<available_skills>\n");
    for (SkillBinding skill : skillBindings) {
      section.append("  <skill>\n");
      section.append("    <name>").append(escapeXml(skill.name())).append("</name>\n");
      section
          .append("    <description>")
          .append(escapeXml(skill.description()))
          .append("</description>\n");
      section.append("  </skill>\n");
    }
    section.append("</available_skills>");
    return systemPrompt.isBlank() ? section.toString().stripLeading() : systemPrompt + section;
  }

  private static String escapeXml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static ProviderType toProviderType(AgentProvider provider) {
    AgentProviderType type = provider.getProviderType();
    if (type == null) {
      throw rejection("provider type must not be null");
    }
    return switch (type) {
      case openai -> ProviderType.OPENAI;
      case openai_response -> ProviderType.OPENAI_RESPONSES;
      case anthropic -> ProviderType.ANTHROPIC;
      case google -> ProviderType.GOOGLE;
    };
  }

  private static <T> T require(T value, String message) {
    if (value == null) {
      throw rejection(message);
    }
    return value;
  }

  /** 确定性拒绝：只由显式调用产生，绝不捕获外部基础设施异常。 */
  private static Rejection rejection(String message) {
    return new Rejection(message);
  }

  private static Result rejected(String message) {
    return new TurnResolver.Rejected(new AssistantError(REJECTION_CODE, message));
  }

  private static String messageOrClass(RuntimeException error) {
    String detail = error.getMessage();
    return detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail;
  }

  /** 内部确定性拒绝信号：只在显式拒绝点抛出，由 {@link #resolve} 转换为 {@link Result.Rejected}。 */
  private static final class Rejection extends RuntimeException {
    private Rejection(String message) {
      super(message);
    }
  }
}
