/**
 * 生产环境 Environment Daemon 的 coding capabilities。
 *
 * <p>每次 Invocation 的缺省 cwd 由 Daemon 写入 {@code
 * EnvironmentCapabilityExecutionRequest.workdir}（invocation workspace 的真实路径）；参数省略路径或 workdir
 * 时以它为基准解析，绝对值与越出 workdir 的相对路径照常交给底层文件系统处理。{@link
 * fun.fengwk.kkstudio.harness.daemon.coding.EnvironmentPaths} 只承担缺省 cwd
 * 与目标路径解析，不构成文件系统沙箱；命令与文件系统的业务授权属于 Platform permission。
 *
 * <p>{@code grep}/{@code find} 使用 Java NIO 原生遍历，不跟随符号链接，并由包内规则实现稳定排序、glob 与分层 {@code .gitignore}
 * 语义；它们不启动外部搜索命令。
 *
 * <p>稳定的能力集合：{@code fs.read}、{@code fs.write}、{@code fs.apply-edit}、{@code process.exec}、{@code
 * fs.search}、{@code fs.find}、{@code fs.list-directory}、{@code lsp.goto-definition}、{@code
 * lsp.workspace-symbols}、{@code lsp.java-decompile}。{@code fs.write} 与 {@code fs.apply-edit} 只处理
 * UTF-8 文本并保留既有编码、BOM 与行尾表示；LSP capabilities 使用可选的本机命令 bridge（{@code kkstudio.daemon.lsp-bridge}）；
 * 未配置时返回明确的不可用错误，但 {@code lsp_java_decompile} 对可解析的 class 目标可回退到 {@code javap}。
 *
 * <p>大型或二进制输出通过 {@link fun.fengwk.kkstudio.harness.daemon.coding.ResourceStore}（独立部署时为 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.LocalFileResourceStore}）存储为不可变 Resource，并以 Resource
 * 引用形式发出。
 */
package fun.fengwk.kkstudio.harness.daemon.coding;
