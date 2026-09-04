/**
 * 生产环境 Environment Daemon 的 coding capabilities。
 *
 * <p>所有文件系统操作都必须经过同一个 canonical Environment root 边界（由 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.EnvironmentPathBoundary} 强制执行真实路径、符号链接越界防护与已存在祖先校验）；
 * 命令权限属于 Platform 职责，Daemon 保留不可绕过的 workdir 边界与文件系统安全防线。
 *
 * <p>{@code grep}/{@code find} 使用 Java NIO 原生遍历，不跟随符号链接，并由包内规则实现稳定排序、glob 与分层 {@code .gitignore}
 * 语义；它们不启动外部搜索命令。
 *
 * <p>稳定的能力集合：{@code fs.read}、{@code fs.write}、{@code fs.apply-edit}、{@code fs.apply-patch}、{@code
 * process.exec}、{@code fs.search}、{@code fs.find}、{@code fs.list-directory}、{@code
 * lsp.goto-definition}、{@code lsp.workspace-symbols}、{@code lsp.java-decompile}。{@code
 * fs.apply-patch} 只处理 invocation workspace 内的 UTF-8 文本， 并在全部预检通过后逐文件使用临时替换提交，失败时尽力回滚已提交文件。LSP
 * capabilities 使用可选的本机命令 bridge（{@code kkstudio.daemon.lsp-bridge}）； 未配置时返回明确的不可用错误，但 {@code
 * lsp_java_decompile} 对可解析的 class 目标可回退到 {@code javap}。
 *
 * <p>大型或二进制输出通过 {@link fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore}（独立部署时为 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.LocalFileResourceStore}）存储为不可变 Resource，并以 Resource
 * 引用形式发出。
 */
package fun.fengwk.kkstudio.harness.daemon.coding;
