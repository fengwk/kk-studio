/**
 * Ordered Tool permission 与 Bash 静态 surface 分析。permission key 只接受精确 {@code *} 或模型可见 tool
 * name，工具特定规则按 stable id 选择；文件路径取调用显式的绝对 {@code path}，按 filesystem-root 坐标交给 JGit gitignore
 * 语义（{@code *}/{@code **}/basename/锚定/directory rule）匹配，negation 与 comment/空/无效 pattern
 * 直接拒绝；其他非绝对路径只命中通配规则。这里只生成策略候选，真实文件边界、符号链接与进程隔离由 Environment Daemon 在执行入口落实。
 *
 * <p>部署范围内唯一的 normalized snapshot（{@link
 * fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettings}）由同包的 {@link
 * fun.fengwk.kkstudio.platform.harness.tool.permission.ToolSettingsProvider} 现读注入，本包只负责规则表达、顺序求值与
 * surface 分析。
 */
package fun.fengwk.kkstudio.platform.harness.tool.permission;
