/**
 * Daemon 本地 Skills 发现、持久目录与精确正文加载。
 *
 * <p>Skill 来源由 Platform 的受管配置决定：PATH 来源按目标 OS 绝对路径或 {@code ~/} 前缀扫描，GIT 来源经宿主 {@code git} 安装/更新到
 * {@code --data-dir} 下的不可变 checkout。发现结果构建完整候选快照后才发布；同名冲突、非法 SKILL.md 与 Git 失败都不会 留下半成品目录，坏 skill
 * 只形成有界诊断。
 *
 * <p>正文按内容 revision（SKILL.md 原始字节的 SHA-256）存放在不可变 blob 中，{@code skill.load} 必须精确匹配 {@code
 * (sourceId, name, revision)}；不可用的旧 revision 返回 {@code RESOURCE_CHANGED}，绝不以当前版本冒充。 {@code
 * skill.source.refresh/install/update} 是仅供管理执行器使用的 capability，不注册为模型 Tool。
 */
package fun.fengwk.kkstudio.harness.daemon.skill;
