package fun.fengwk.kkstudio.platform.catalog.skill;

import org.jspecify.annotations.Nullable;

/**
 * Git 渠道 Skill Package 的可编辑设置写入：检查 branch 与私有仓库访问令牌。
 *
 * <p>{@code branch} 只用于检查候选更新；{@code token} 为 null 表示匿名访问，绝不回显或进入日志。repository URL 是不可变身份，不在可编辑设置内。
 * 它只由编辑与配置导入路径构造，并总是与 {@code skill_package.version} 的同一次推进一起落库。
 */
public record SkillPackageGitEdit(String branch, @Nullable String token) {}
