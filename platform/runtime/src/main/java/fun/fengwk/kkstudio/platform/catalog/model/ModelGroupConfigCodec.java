package fun.fengwk.kkstudio.platform.catalog.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** {@code model.group_config} 的唯一严格类型化 codec 与结构校验。 */
@Component
public final class ModelGroupConfigCodec {

  private static final int MAX_MEMBERS = 64;

  private final ObjectMapper objectMapper;
  private final ObjectReader reader;

  public ModelGroupConfigCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.reader =
        objectMapper
            .rebuild()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .readerFor(StoredGroup.class);
  }

  public ModelGroupConfigDTO decode(String json) {
    if (json == null || json.isBlank()) {
      throw DomainException.validation("model", "group config must not be blank");
    }
    try {
      StoredGroup stored = reader.readValue(json);
      ModelGroupConfigDTO group = toDto(stored);
      validateShape(group);
      return group;
    } catch (DatabindException error) {
      throw DomainException.validation(
          "model", "group config is invalid: " + error.getOriginalMessage(), error);
    } catch (JacksonException error) {
      throw DomainException.validation("model", "group config must be valid JSON object", error);
    }
  }

  public String encode(ModelGroupConfigDTO group) {
    validateShape(group);
    try {
      List<StoredMember> members =
          group.getMembers().stream()
              .map(member -> new StoredMember(member.getModelName(), member.getVariant()))
              .toList();
      return objectMapper.writeValueAsString(
          new StoredGroup(members, group.getFallbackStatusCodes(), group.getCooldownSeconds()));
    } catch (JacksonException error) {
      throw new IllegalStateException("cannot encode model group config", error);
    }
  }

  /**
   * 组结构校验：有序成员非空、引用字段 canonical、同引用不重复；状态码 400–599 且不重复；冷却为非负整数秒。
   *
   * <p>成员是否真实存在、是否为单 MODEL 以及 Variant 是否存在由写入服务在持锁事务内另行校验。
   */
  public void validateShape(ModelGroupConfigDTO group) {
    if (group == null) {
      throw DomainException.validation("model", "group config is required");
    }
    List<ModelGroupMemberDTO> members = group.getMembers();
    if (members == null || members.isEmpty()) {
      throw DomainException.validation("model", "group.members must not be empty");
    }
    if (members.size() > MAX_MEMBERS) {
      throw DomainException.validation("model", "group.members must not exceed " + MAX_MEMBERS);
    }
    Set<String> seen = new HashSet<>();
    List<ModelGroupMemberDTO> checked = new ArrayList<>(members.size());
    for (int index = 0; index < members.size(); index++) {
      ModelGroupMemberDTO member = members.get(index);
      if (member == null) {
        throw DomainException.validation("model", "group.members[" + index + "] must not be null");
      }
      String modelName =
          requireCanonical(member.getModelName(), "group.members[" + index + "].modelName");
      String variant =
          requireCanonical(member.getVariant(), "group.members[" + index + "].variant");
      if (!seen.add(modelName + '\u0000' + variant)) {
        throw DomainException.validation(
            "model", "group.members must not repeat a member reference");
      }
      ModelGroupMemberDTO normalized = new ModelGroupMemberDTO();
      normalized.setModelName(modelName);
      normalized.setVariant(variant);
      checked.add(normalized);
    }
    group.setMembers(checked);

    List<Integer> rawCodes = group.getFallbackStatusCodes();
    if (rawCodes == null) {
      throw DomainException.validation("model", "group.fallbackStatusCodes is required");
    }
    Set<Integer> codes = new HashSet<>();
    for (Integer code : rawCodes) {
      if (code == null || code < 400 || code > 599) {
        throw DomainException.validation(
            "model", "group.fallbackStatusCodes must contain only HTTP error statuses 400-599");
      }
      if (!codes.add(code)) {
        throw DomainException.validation(
            "model", "group.fallbackStatusCodes must not contain duplicate statuses");
      }
    }
    group.setFallbackStatusCodes(List.copyOf(rawCodes));

    Integer cooldown = group.getCooldownSeconds();
    if (cooldown == null || cooldown < 0) {
      throw DomainException.validation(
          "model", "group.cooldownSeconds must be a non-negative integer");
    }
  }

  private static ModelGroupConfigDTO toDto(StoredGroup stored) {
    if (stored == null) {
      return null;
    }
    ModelGroupConfigDTO group = new ModelGroupConfigDTO();
    if (stored.members() != null) {
      List<ModelGroupMemberDTO> members = new ArrayList<>(stored.members().size());
      for (StoredMember storedMember : stored.members()) {
        if (storedMember == null) {
          members.add(null);
        } else {
          ModelGroupMemberDTO member = new ModelGroupMemberDTO();
          member.setModelName(storedMember.modelName());
          member.setVariant(storedMember.variant());
          members.add(member);
        }
      }
      group.setMembers(members);
    }
    group.setFallbackStatusCodes(stored.fallbackStatusCodes());
    group.setCooldownSeconds(stored.cooldownSeconds());
    return group;
  }

  private record StoredGroup(
      List<StoredMember> members, List<Integer> fallbackStatusCodes, Integer cooldownSeconds) {
    /** Jackson 反序列化回调：异常会被包装成 DatabindException，再由 decode 统一翻译为 validation。 */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown model group field: " + name);
    }
  }

  private record StoredMember(String modelName, String variant) {
    /** Jackson 反序列化回调：异常会被包装成 DatabindException，再由 decode 统一翻译为 validation。 */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
      throw new IllegalArgumentException("unknown model group member field: " + name);
    }
  }

  private static String requireCanonical(String value, String field) {
    if (value == null || value.isBlank()) {
      throw DomainException.validation("model", field + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw DomainException.validation("model", field + " must not contain surrounding whitespace");
    }
    if (value.length() > 128) {
      throw DomainException.validation("model", field + " must be <= 128 characters");
    }
    return value;
  }
}
