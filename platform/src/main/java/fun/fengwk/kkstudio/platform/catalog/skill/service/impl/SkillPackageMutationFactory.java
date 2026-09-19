package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillRevisions;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillRevision;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.skill.SkillDefinitionDTO;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 把 Skill package 请求体规范化为不可变的 package 版本与其 revision 行。
 *
 * <p>contentRevision 与 packageRevision 都由服务端据精确内容重算，绝不接受客户端提供的 revision；同一 package 内 Skill 名
 * 必须唯一，且至少一个 Skill。
 */
@Component
final class SkillPackageMutationFactory {

  static final String RESOURCE = "skill_package";

  private final AgentEditableSupport editableSupport;

  SkillPackageMutationFactory(AgentEditableSupport editableSupport) {
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
  }

  /** 规范化一个 package 版本：校验 package 身份与完整 Skill 列表，并计算两个 revision。 */
  Mutation newMutation(
      String packageName,
      String packageVersion,
      String description,
      List<SkillDefinitionDTO> skills) {
    String canonicalName = requirePackageName(packageName);
    String canonicalVersion = requirePackageVersion(packageVersion);
    String canonicalDescription = editableSupport.trimToNull(description);
    List<SkillRevision> revisions = requireSkills(canonicalName, canonicalVersion, skills);
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setPackageName(canonicalName);
    skillPackage.setPackageVersion(canonicalVersion);
    skillPackage.setDescription(canonicalDescription);
    skillPackage.setPackageRevision(SkillRevisions.packageRevision(skillPackage, revisions));
    return new Mutation(skillPackage, revisions);
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

  private List<SkillRevision> requireSkills(
      String packageName, String packageVersion, List<SkillDefinitionDTO> skills) {
    if (skills == null || skills.isEmpty()) {
      throw new AiValidationException(RESOURCE, "skills must contain at least one definition");
    }
    Set<String> seen = new HashSet<>();
    List<SkillRevision> revisions = new ArrayList<>(skills.size());
    for (SkillDefinitionDTO definition : skills) {
      if (definition == null) {
        throw new AiValidationException(RESOURCE, "skills must not contain null elements");
      }
      String name = requireSkillName(definition.getName());
      if (!seen.add(name)) {
        throw new AiValidationException(
            RESOURCE, "skills must not contain duplicate names: " + name);
      }
      SkillRevision revision = new SkillRevision();
      revision.setPackageName(packageName);
      revision.setPackageVersion(packageVersion);
      revision.setName(name);
      revision.setDescription(requireSkillDescription(name, definition.getDescription()));
      revision.setContent(requireSkillContent(name, definition.getContent()));
      revision.setContentRevision(SkillRevisions.contentRevision(revision.getContent()));
      revisions.add(revision);
    }
    return List.copyOf(revisions);
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

  /** 一次规范化结果：不可变 package 版本与其完整 revision 列表（顺序与请求一致）。 */
  record Mutation(SkillPackage skillPackage, List<SkillRevision> revisions) {}
}
