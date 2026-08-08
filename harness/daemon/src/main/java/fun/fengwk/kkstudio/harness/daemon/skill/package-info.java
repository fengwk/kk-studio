/**
 * Daemon 本地 Skills 发现与正文加载（LangChain4j {@code FileSystemSkillLoader} 适配）。
 *
 * <p>skills 仅通过 CLI {@code --skill-dir}（可重复）或默认 {@code ~/.agents/skills} 扫描；不接受服务端下发的路径配置。 READY
 * 仅上报 name/description，skill 指令正文（SKILL.md 去除 front matter）通过 {@code LOAD_SKILL} 协议按需返回。
 */
package fun.fengwk.kkstudio.harness.daemon.skill;
