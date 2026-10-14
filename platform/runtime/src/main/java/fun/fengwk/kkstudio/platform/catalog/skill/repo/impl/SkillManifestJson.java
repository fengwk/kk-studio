package fun.fengwk.kkstudio.platform.catalog.skill.repo.impl;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;

import java.util.List;

/**
 * {@code skill_package.skills} jsonb 列与 Skill manifest 之间的确定性编解码。
 *
 * <p>元素形状只由应用严格验证（数据库仅断言 {@code jsonb_typeof(skills) = 'array'}）：编码按 name 升序输出 {@code [{name,
 * description}]}，解码拒绝未知字段与缺失字段，因此被外部改写过的行无法伪装成合法 manifest。
 */
final class SkillManifestJson {

  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private static final TypeReference<List<SkillManifestEntry>> MANIFEST_TYPE =
      new TypeReference<>() {};

  private SkillManifestJson() {}

  static String encode(List<SkillManifestEntry> manifest) {
    try {
      return OBJECT_MAPPER.writeValueAsString(manifest);
    } catch (JacksonException error) {
      throw new IllegalArgumentException("skill manifest cannot be serialized", error);
    }
  }

  static List<SkillManifestEntry> decode(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      List<SkillManifestEntry> manifest = OBJECT_MAPPER.readValue(json, MANIFEST_TYPE);
      if (manifest == null || manifest.stream().anyMatch(entry -> entry == null)) {
        throw new IllegalStateException("stored skill manifest is invalid");
      }
      return List.copyOf(manifest);
    } catch (JacksonException error) {
      throw new IllegalStateException("stored skill manifest is invalid", error);
    }
  }
}
