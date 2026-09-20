package fun.fengwk.kkstudio.platform.harness.task;

import java.util.Objects;

/**
 * 仅供 Prompt 渲染的 Skill 三元组；正文由统一 read 按稳定 path 读取。
 *
 * @param name Skill 名称
 * @param description Skill 描述
 * @param path Skill 文件的稳定读取路径（例如 {@code kkstudio:/skills/<packageName>/<skillName>/SKILL.md}）
 */
public record SkillPromptEntry(String name, String description, String path) {

  public SkillPromptEntry {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(path, "path");
  }
}
