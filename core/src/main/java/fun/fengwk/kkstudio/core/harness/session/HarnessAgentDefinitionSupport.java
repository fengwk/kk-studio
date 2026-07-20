package fun.fengwk.kkstudio.core.harness.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.context.SelectedSkillMetadata;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从当前 AgentDefinition 装载 Turn 所需的动态配置（prompt/tools/skills/policy/environment）。
 *
 * <p>Thread 只持久化 agent id/name 与 model/variant/yolo；完整配置与 Skill 元数据不进 Entry Tree。
 */
@Component
public class HarnessAgentDefinitionSupport {
  private final AgentDefinitionMapper agentDefinitionMapper;
  private final ObjectMapper objectMapper;
  private final LiveEnvironmentRegistry environmentRegistry;

  public HarnessAgentDefinitionSupport(
      AgentDefinitionMapper agentDefinitionMapper,
      ObjectMapper objectMapper,
      LiveEnvironmentRegistry environmentRegistry) {
    this.agentDefinitionMapper =
        Objects.requireNonNull(agentDefinitionMapper, "agentDefinitionMapper");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  public AgentDefinitionDO requireDefinition(long agentDefinitionId) {
    AgentDefinitionDO definition = agentDefinitionMapper.getById(agentDefinitionId);
    if (definition == null) {
      throw new IllegalArgumentException("unknown agent definition: " + agentDefinitionId);
    }
    return definition;
  }

  public AgentRuntimeConfig runtimeConfig(AgentThread thread) {
    Objects.requireNonNull(thread, "thread");
    if (thread.activeAgentDefinitionId() == null) {
      throw new IllegalStateException(
          "thread has no active agent: " + thread.id() + "; apply SET_AGENT first");
    }
    return runtimeConfig(
        requireDefinition(thread.activeAgentDefinitionId()),
        thread.modelId(),
        thread.variant(),
        thread.yoloEnabled());
  }

  public AgentRuntimeConfig runtimeConfig(
      AgentDefinitionDO definition, String modelId, String variant, boolean yoloEnabled) {
    Objects.requireNonNull(definition, "definition");
    ParsedConfig parsed = parseConfig(definition);
    String resolvedModelId =
        modelId == null || modelId.isBlank() ? String.valueOf(definition.getModelId()) : modelId;
    String resolvedVariant =
        variant == null || variant.isBlank() ? definition.getVariant() : variant;
    List<SelectedSkillMetadata> selectedSkills =
        resolveSelectedSkills(parsed.skills(), parsed.environmentName());
    return new AgentRuntimeConfig(
        definition.getId(),
        definition.getSystemPrompt(),
        resolvedModelId,
        resolvedVariant,
        parsed.environmentName(),
        parsed.tools(),
        parsed.skills(),
        selectedSkills,
        parsed.allowedSubagents(),
        parsed.executionPolicyJson(),
        yoloEnabled);
  }

  public ParsedConfig parseConfig(AgentDefinitionDO definition) {
    Objects.requireNonNull(definition, "definition");
    try {
      JsonNode config = objectMapper.readTree(definition.getConfigJson());
      JsonNode policy = config.path("executionPolicy");
      String environmentName = textOrNull(config.path("environmentName"));
      return new ParsedConfig(
          environmentName,
          strings(config, "tools"),
          strings(config, "skills"),
          strings(config, "allowedSubagents"),
          objectMapper.writeValueAsString(
              policy.isMissingNode() ? objectMapper.createObjectNode() : policy));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("agent config is invalid", error);
    }
  }

  /**
   * Resolves selected short skill names platform-first, then selected Environment. Platform names
   * shadow Environment same names.
   */
  private List<SelectedSkillMetadata> resolveSelectedSkills(
      List<String> skillNames, String environmentName) {
    if (environmentName != null) {
      requireReadyEnvironment(environmentName);
    }
    if (skillNames.isEmpty()) {
      return List.of();
    }
    Map<String, SelectedSkillMetadata> catalog = new LinkedHashMap<>();
    environmentRegistry
        .find(LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME)
        .filter(LiveEnvironment::isReady)
        .ifPresent(
            platform -> {
              for (DaemonSkillDescriptor skill : platform.skills()) {
                catalog.putIfAbsent(
                    skill.name(),
                    new SelectedSkillMetadata(
                        skill.name(), skill.description(), platform.environmentName()));
              }
            });
    if (environmentName != null
        && !LiveEnvironmentRegistry.PLATFORM_ENVIRONMENT_NAME.equals(environmentName)) {
      LiveEnvironment selected = requireReadyEnvironment(environmentName);
      for (DaemonSkillDescriptor skill : selected.skills()) {
        catalog.putIfAbsent(
            skill.name(),
            new SelectedSkillMetadata(
                skill.name(), skill.description(), selected.environmentName()));
      }
    }
    List<SelectedSkillMetadata> resolved = new ArrayList<>(skillNames.size());
    for (String name : skillNames) {
      SelectedSkillMetadata skill = catalog.get(name);
      if (skill == null) {
        throw new IllegalArgumentException("selected agent skill is unavailable: " + name);
      }
      resolved.add(skill);
    }
    return List.copyOf(resolved);
  }

  private LiveEnvironment requireReadyEnvironment(String environmentName) {
    return environmentRegistry
        .find(environmentName)
        .filter(LiveEnvironment::isReady)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "agent environment is offline or missing: " + environmentName));
  }

  private static List<String> strings(JsonNode config, String name) {
    JsonNode values = config.path(name);
    if (values.isMissingNode() || values.isNull()) {
      return List.of();
    }
    if (!values.isArray()) {
      throw new IllegalArgumentException("agent config " + name + " must be an array");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual() || value.textValue().isBlank()) {
        throw new IllegalArgumentException(
            "agent config " + name + " must contain non-blank strings");
      }
      result.add(value.textValue());
    }
    return List.copyOf(result);
  }

  private static String textOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new IllegalArgumentException("agent config environmentName must be a string");
    }
    String value = node.textValue();
    return value == null || value.isBlank() ? null : value;
  }

  public record ParsedConfig(
      String environmentName,
      List<String> tools,
      List<String> skills,
      List<String> allowedSubagents,
      String executionPolicyJson) {
    public ParsedConfig {
      tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
      skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
      allowedSubagents = List.copyOf(Objects.requireNonNull(allowedSubagents, "allowedSubagents"));
      executionPolicyJson = Objects.requireNonNull(executionPolicyJson, "executionPolicyJson");
      if (environmentName != null && environmentName.isBlank()) {
        throw new IllegalArgumentException("environmentName must not be blank when present");
      }
    }
  }
}
