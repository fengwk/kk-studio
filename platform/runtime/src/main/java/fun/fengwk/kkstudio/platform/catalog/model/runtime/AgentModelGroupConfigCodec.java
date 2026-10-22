package fun.fengwk.kkstudio.platform.catalog.model.runtime;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** {@code agent_model.group_config} 的唯一严格类型化 codec 与结构校验。 */
@Component
public final class AgentModelGroupConfigCodec {

  private static final int MAX_MEMBERS = 64;

  private final ObjectMapper objectMapper;
  private final ObjectReader reader;

  public AgentModelGroupConfigCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.reader =
        objectMapper
            .rebuild()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .readerFor(AgentModelGroupDTO.class);
  }

  public AgentModelGroupDTO decode(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("group config must not be blank");
    }
    try {
      AgentModelGroupDTO group = reader.readValue(json);
      validateShape(group);
      return group;
    } catch (DatabindException error) {
      throw new IllegalArgumentException(
          "group config is invalid: " + error.getOriginalMessage(), error);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("group config must be valid JSON object", error);
    }
  }

  public String encode(AgentModelGroupDTO group) {
    validateShape(group);
    try {
      return objectMapper.writeValueAsString(group);
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode model group config", error);
    }
  }

  /**
   * 组结构校验：有序成员非空、引用字段 canonical、同引用不重复；状态码 400–599 且不重复；冷却为非负整数秒。
   *
   * <p>成员是否真实存在、是否为单 MODEL 以及 Variant 是否存在由写入服务在持锁事务内另行校验。
   */
  public void validateShape(AgentModelGroupDTO group) {
    if (group == null) {
      throw new IllegalArgumentException("group config is required");
    }
    List<AgentModelGroupMemberDTO> members = group.getMembers();
    if (members == null || members.isEmpty()) {
      throw new IllegalArgumentException("group.members must not be empty");
    }
    if (members.size() > MAX_MEMBERS) {
      throw new IllegalArgumentException("group.members must not exceed " + MAX_MEMBERS);
    }
    Set<String> seen = new HashSet<>();
    List<AgentModelGroupMemberDTO> checked = new ArrayList<>(members.size());
    for (int index = 0; index < members.size(); index++) {
      AgentModelGroupMemberDTO member = members.get(index);
      if (member == null) {
        throw new IllegalArgumentException("group.members[" + index + "] must not be null");
      }
      String modelName =
          requireCanonical(member.getModelName(), "group.members[" + index + "].modelName");
      String variant =
          requireCanonical(member.getVariant(), "group.members[" + index + "].variant");
      if (!seen.add(modelName + '\u0000' + variant)) {
        throw new IllegalArgumentException("group.members must not repeat a member reference");
      }
      AgentModelGroupMemberDTO normalized = new AgentModelGroupMemberDTO();
      normalized.setModelName(modelName);
      normalized.setVariant(variant);
      checked.add(normalized);
    }
    group.setMembers(checked);

    List<Integer> rawCodes = group.getFallbackStatusCodes();
    if (rawCodes == null) {
      throw new IllegalArgumentException("group.fallbackStatusCodes is required");
    }
    Set<Integer> codes = new HashSet<>();
    for (Integer code : rawCodes) {
      if (code == null || code < 400 || code > 599) {
        throw new IllegalArgumentException(
            "group.fallbackStatusCodes must contain only HTTP error statuses 400-599");
      }
      if (!codes.add(code)) {
        throw new IllegalArgumentException(
            "group.fallbackStatusCodes must not contain duplicate statuses");
      }
    }
    group.setFallbackStatusCodes(List.copyOf(rawCodes));

    Integer cooldown = group.getCooldownSeconds();
    if (cooldown == null || cooldown < 0) {
      throw new IllegalArgumentException("group.cooldownSeconds must be a non-negative integer");
    }
  }

  private static String requireCanonical(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(field + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw new IllegalArgumentException(field + " must be <= 128 characters");
    }
    return value;
  }
}
