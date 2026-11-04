package fun.fengwk.kkstudio.harness.spi;

import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.function.Consumer;

/**
 * Platform VFS 的窄执行端口：{@code vfs:} 路径上的文件工具操作全部经由本端口执行。
 *
 * <p>文件工具（{@code read}、{@code write}、{@code edit}、{@code find}、{@code grep}）按每次调用的 {@code path}
 * 参数路由：{@code vfs:} 前缀走本端口，绝对宿主路径走绑定的 {@code BoundEnvironment}。因此本端口不接收 Environment，也不需要调用方
 * 先确定环境是否在线。
 *
 * <p>实现方是 Platform VFS：命名空间、只读前缀、会话归属、revision 冲突与扫描预算都由实现方负责；Harness 只负责按参数路由，并在实现方 返回错误结果时保留其文案。
 *
 * <p>语义与同名 Environment capability 一致：参数 schema、超时、分页窗口与执行事实的表述保持同一套约定，区别只在于数据来自平台侧存储。 阻塞方法在取消、Stop
 * 或租约丢失时表现为对该线程的 interrupt，实现必须抛出 {@link InterruptedException}。
 */
public interface VfsGateway {

  /** {@code read}：读取文件、目录列表或受支持的媒体资源。 */
  ToolResult read(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;

  /** {@code write}：整体创建或覆盖会话目录中的文本文件。 */
  ToolResult write(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;

  /** {@code edit}：在会话目录文本文件上按精确匹配替换。 */
  ToolResult edit(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;

  /** {@code find}：按路径匹配会话目录内文件。 */
  ToolResult find(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;

  /** {@code grep}：在有扫描预算的会话目录内检索文本。 */
  ToolResult grep(ToolExecutionRequest request, Consumer<ToolResult> progress)
      throws InterruptedException;
}
