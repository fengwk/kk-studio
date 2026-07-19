package fun.fengwk.kkstudio.core.harness.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.harness.runtime.context.AgentRuntimeConfig;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 从当前 AgentDefinition 装载 Turn 所需的动态配置（prompt/tools/skills/policy）。
 *
 * <p>Thread 只持久化 agent id/name 与 model/variant/yolo；完整配置不进 Entry Tree。
 */
@Component
public class HarnessAgentDefinitionSupport {
  private final AgentDefinitionMapper agentDefinitionMapper;
  private final ObjectMapper objectMapper;

  public HarnessAgentDefinitionSupport(
      AgentDefinitionMapper agentDefinitionMapper, ObjectMapper objectMapper) {
    this.agentDefinitionMapper =
        Objects.requireNonNull(agentDefinitionMapper, "agentDefinitionMapper");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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
    return new AgentRuntimeConfig(
        definition.getId(),
        definition.getSystemPrompt(),
        resolvedModelId,
        resolvedVariant,
        parsed.tools(),
        parsed.skills(),
        parsed.allowedSubagents(),
        parsed.executionPolicyJson(),
        yoloEnabled);
  }

  public ParsedConfig parseConfig(AgentDefinitionDO definition) {
    Objects.requireNonNull(definition, "definition");
    try {
      JsonNode config = objectMapper.readTree(definition.getConfigJson());
      JsonNode policy = config.path("executionPolicy");
      return new ParsedConfig(
          strings(config, "tools"),
          strings(config, "skills"),
          strings(config, "allowedSubagents"),
          objectMapper.writeValueAsString(
              policy.isMissingNode() ? objectMapper.createObjectNode() : policy));
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("agent config is invalid", error);
    }
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

  public record ParsedConfig(
      List<String> tools,
      List<String> skills,
      List<String> allowedSubagents,
      String executionPolicyJson) {
    public ParsedConfig {
      tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
      skills = List.copyOf(Objects.requireNonNull(skills, "skills"));
      allowedSubagents = List.copyOf(Objects.requireNonNull(allowedSubagents, "allowedSubagents"));
      executionPolicyJson = Objects.requireNonNull(executionPolicyJson, "executionPolicyJson");
    }
  }
}
