/**
 * Ordered Tool permission 与 Bash 静态 surface 分析。path target 只按单次调用的 effective workdir 解析为相对 POSIX
 * 路径，交给 JGit gitignore 语义（{@code *}/{@code **}/basename/锚定/directory rule）匹配；negation 与
 * comment/空/无效 pattern 直接拒绝。permission key 只接受精确 {@code *} 或 canonical {@code AgentToolId}，工具特定规则按
 * stable id 选择。这里只生成策略候选，不承担 T09 的真实路径、symlink 或执行沙箱安全。
 */
package fun.fengwk.kkstudio.harness.runtime.permission;
