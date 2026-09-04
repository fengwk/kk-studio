/**
 * Daemon 本地 Skills 发现与正文加载（LangChain4j {@code FileSystemSkillLoader} 适配）。
 *
 * <p>Skill 仅通过 CLI {@code --skill-dir}（可重复）或默认 {@code ~/.agents/skills} 扫描并在此后冻结；绝不接受 Gateway
 * 下发的任意本地路径配置。 握手 {@code READY} 仅上报 name 与 description，Skill 指令正文（SKILL.md 去除 front matter）通过原子
 * Capability {@code skill.load} 按需读取并返回。
 */
package fun.fengwk.kkstudio.harness.daemon.skill;
