/**
 * 生产环境 Environment Daemon 的 coding capabilities。
 *
 * <p>所有文件系统操作都必须经过同一个 canonical environment root 边界；命令权限属于 Platform 职责，Daemon 保留不可绕过的 workdir 边界。
 *
 * <p>{@code grep}/{@code find} 使用 Java NIO 原生遍历，不跟随符号链接，并由包内规则实现稳定排序、glob 与分层 {@code .gitignore}
 * 语义；它们不启动外部搜索命令。
 *
 * <p>稳定的能力集合：{@code fs.read}、{@code fs.write}、{@code fs.apply-edit}、{@code fs.apply-patch}、{@code
 * process.exec}、{@code fs.search}、{@code fs.find}、{@code lsp.goto-definition}、{@code
 * lsp.workspace-symbols}、{@code lsp.java-decompile}。{@code fs.apply-patch} 只处理 invocation workspace 内的 UTF-8 文本，并在全部预检通过后提交变更。LSP
 * capabilities 使用可选的本机命令 bridge（{@code kkstudio.daemon.lsp-bridge}）；未配置时返回明确的不可用错误，但 {@code
 * lsp_java_decompile} 对可解析的 class 目标可回退到 {@code javap}。
 *
 * <p>大型或二进制输出通过 {@link fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore}（独立部署时为 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.LocalFileResourceStore}）存储为不可变 resources，并以 {@code
 * capability resource content 引用形式发出。
 */
package fun.fengwk.kkstudio.harness.daemon.coding;
