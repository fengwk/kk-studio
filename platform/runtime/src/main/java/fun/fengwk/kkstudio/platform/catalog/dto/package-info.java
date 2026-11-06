/**
 * Platform 目录族的 HTTP DTO：MCP server 与技能包。
 *
 * <p>这里的类型由 web 的 HTTP 边界统一校验必填与未知字段；秘密字段一律以 {@code SecretUpdate} 提交、以 {@code {configured}}
 * 返回，绝不回显值。技能包只暴露仓库身份、branch 检查观察值与当前版本派生的 manifest，不承载 Skill 正文。
 */
package fun.fengwk.kkstudio.platform.catalog.dto;
