/**
 * 生产环境 Environment Daemon 的 coding capabilities。
 *
 * <p>需要目录的能力把目标 Daemon 上的显式绝对 workdir 放在自身 arguments 中；{@link
 * fun.fengwk.kkstudio.harness.daemon.coding.EnvironmentPaths} 按本机 OS 词法校验它（拒绝周边空白与未展开占位符），
 * 再要求现存、为目录且可读，然后解析目标路径。绝对路径与越出 workdir 的相对路径照常交给底层文件系统处理，不构成文件系统沙箱； 命令与文件系统的业务授权属于 Platform
 * permission。调用之间不继承目录。
 *
 * <p>{@code grep}/{@code find} 使用 Java NIO 原生遍历，不跟随符号链接，并由包内规则实现稳定排序、glob 与分层 {@code .gitignore}
 * 语义；它们不启动外部搜索命令。
 *
 * <p>稳定的能力集合：{@code fs.read}、{@code fs.write}、{@code fs.edit}、{@code process.exec}、{@code
 * fs.grep}、{@code fs.find}、{@code lsp.goto-definition}、{@code lsp.workspace-symbols}、{@code
 * lsp.java-decompile}。{@code fs.write} 按调用内容原样写入并保留既有文件的编码与 BOM；{@code fs.edit}
 * 额外保留未修改区域与替换引入的行尾表示；两者都只处理普通文本文件，并在原子替换时继承既有文件的 POSIX 权限。LSP capabilities 由 {@link
 * fun.fengwk.kkstudio.harness.daemon.coding.LspService} 承载：按 CLI 的 {@code --lsp-config}
 * 预先配置的服务器自动选择项目根、复用客户端并同步文档，未配置或服务器未安装时返回明确的不可用错误， 不做 {@code javap} 字节码回退。
 *
 * <p>大文本输出由 {@link fun.fengwk.kkstudio.harness.daemon.coding.TextOutputStore} 保存在 data
 * directory，并返回有界 preview 与本地可分页读取路径；图片等二进制结果以内存 {@code BinaryResultContent} 交给 Daemon 运行时，经预签名 PUT
 * 直传全局对象存储，不在本包落盘或通过 WebSocket 携带字节。
 */
package fun.fengwk.kkstudio.harness.daemon.coding;
