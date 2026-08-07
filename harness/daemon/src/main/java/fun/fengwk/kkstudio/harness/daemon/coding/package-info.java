/**
 * 生产环境 Environment Daemon 的 coding tools。
 *
 * <p>所有文件系统操作都必须经过同一个 canonical environment root 边界；命令权限属于 Platform 职责，Daemon 保留不可绕过的 workdir 边界。
 *
 * <p>稳定的能力集合：{@code read}、{@code write}、{@code edit}、{@code bash}、{@code grep}、 {@code find}、{@code
 * lsp_goto_definition}、{@code lsp_workspace_symbols}、{@code lsp_java_decompile}。LSP tools 使用可选的本机命令
 * bridge（{@code kkstudio.daemon.lsp-bridge}）；未配置时返回明确的不可用错误，但 {@code lsp_java_decompile} 对可解析的
 * class 目标可回退到 {@code javap}。
 *
 * <p>大型或二进制输出通过 {@link fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore}（独立部署时为 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.LocalFileResourceStore}）存储为不可变 resources，并以 {@code
 * ResourceToolContent} 引用形式发出。
 */
package fun.fengwk.kkstudio.harness.daemon.coding;
