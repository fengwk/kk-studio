package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.Skill;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.skill.SkillDefinitionDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把 Skill package 请求体规范化为一个 package 版本与其 Skill 内容行。
 *
 * <p>请求体是完整替换的唯一事实：同一 package 内 Skill 名必须唯一，且至少一个 Skill。正文按提交的精确字节保存，不引入额外内容标识。
 */
@Component
final class SkillPackageMutationFactory {

  static final String RESOURCE = "skill_package";

  private final AgentEditableSupport editableSupport;

  SkillPackageMutationFactory(AgentEditableSupport editableSupport) {
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
  }

  /** 规范化一个 package 版本：校验 package 身份与完整 Skill 列表。 */
  Mutation newMutation(
      String packageName,
      String packageVersion,
      String description,
      List<SkillDefinitionDTO> skills) {
    String canonicalName = requirePackageName(packageName);
    String canonicalVersion = requirePackageVersion(packageVersion);
    String canonicalDescription = editableSupport.trimToNull(description);
    List<Skill> contents = requireSkills(canonicalName, canonicalVersion, skills);
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setPackageName(canonicalName);
    skillPackage.setPackageVersion(canonicalVersion);
    skillPackage.setDescription(canonicalDescription);
    return new Mutation(skillPackage, contents);
  }

  private String requirePackageName(String raw) {
    try {
      return SkillNames.canonicalPackageName(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "invalid package name: " + error.getMessage(), error);
    }
  }

  private String requirePackageVersion(String raw) {
    try {
      return SkillNames.canonicalPackageVersion(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "invalid package version: " + error.getMessage(), error);
    }
  }

  private List<Skill> requireSkills(
      String packageName, String packageVersion, List<SkillDefinitionDTO> skills) {
    if (skills == null || skills.isEmpty()) {
      throw new AiValidationException(RESOURCE, "skills must contain at least one definition");
    }
    Set<String> seen = new HashSet<>();
    List<Skill> contents = new ArrayList<>(skills.size());
    for (SkillDefinitionDTO definition : skills) {
      if (definition == null) {
        throw new AiValidationException(RESOURCE, "skills must not contain null elements");
      }
      String name = requireSkillName(definition.getName());
      if (!seen.add(name)) {
        throw new AiValidationException(
            RESOURCE, "skills must not contain duplicate names: " + name);
      }
      Skill skill = new Skill();
      skill.setPackageName(packageName);
      skill.setPackageVersion(packageVersion);
      skill.setName(name);
      skill.setDescription(requireSkillDescription(name, definition.getDescription()));
      skill.setContent(requireSkillContent(name, definition.getContent()));
      contents.add(skill);
    }
    return List.copyOf(contents);
  }

  private String requireSkillName(String raw) {
    try {
      return SkillNames.canonicalSkillName(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, "invalid skill name: " + error.getMessage(), error);
    }
  }

  private String requireSkillDescription(String name, String raw) {
    try {
      return SkillNames.canonicalDescription(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "invalid description for skill " + name + ": " + error.getMessage(), error);
    }
  }

  private String requireSkillContent(String name, String raw) {
    try {
      return SkillNames.canonicalContent(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "invalid content for skill " + name + ": " + error.getMessage(), error);
    }
  }

  /** 一次规范化结果：package 版本与其完整 Skill 内容列表（顺序与请求一致）。 */
  record Mutation(SkillPackage skillPackage, List<Skill> skills) {}
}
