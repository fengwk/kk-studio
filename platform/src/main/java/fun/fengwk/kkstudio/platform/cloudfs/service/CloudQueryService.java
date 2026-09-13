package fun.fengwk.kkstudio.platform.cloudfs.service;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.tool.SearchControl;

import java.time.Duration;
import java.util.List;

/**
 * Cloud File System 共享查询与检索服务。
 *
 * <p>为 Agent 工具（{@code cloud_read}、{@code cloud_find}、{@code cloud_grep}）与 REST 控制器 提供统一的按行分页、RE2/J
 * 正则/字面量检索、Glob 文件发现及边界约束实现，杜绝重复代码。
 */
public interface CloudQueryService {

  record FindResult(List<String> paths, boolean limited) {}

  record GrepMatch(CloudPath path, int lineNumber, String content) {}

  record GrepResult(List<GrepMatch> matches, boolean limited) {}

  record TextLine(int lineNumber, String content, boolean truncated) {}

  record TextWindow(
      int offset,
      int limit,
      int totalLines,
      Integer nextOffset,
      boolean endsWithNewline,
      boolean hasTruncatedLine,
      List<TextLine> lines,
      String content) {}

  /**
   * 在指定虚拟目录下按 glob 模式查找路径。
   *
   * @param rootPath 搜索起始目录（不能为 {@code /.artifacts}）
   * @param pattern Glob 模式
   * @param limit 结果上限
   * @param timeout 超时时间
   * @return 发现结果
   */
  FindResult find(CloudPath rootPath, String pattern, int limit, Duration timeout);

  /**
   * 在指定虚拟目录下按 glob 模式查找路径，接受外部 {@link SearchControl}。
   *
   * @param rootPath 搜索起始目录（不能为 {@code /.artifacts}）
   * @param pattern Glob 模式
   * @param limit 结果上限
   * @param control 搜索控制
   * @return 发现结果
   */
  FindResult find(CloudPath rootPath, String pattern, int limit, SearchControl control)
      throws InterruptedException;

  /**
   * 在指定路径下检索文本内容（RE2/J 或字面量）。
   *
   * @param rootPath 检索起始路径（可为目录、单个文本文件或精确 canonical .txt artifact 路径）
   * @param pattern 正则或字面量子串
   * @param include 可选文件名过滤 glob
   * @param ignoreCase 是否忽略大小写
   * @param literal 是否字面量
   * @param multiline 是否多行匹配
   * @param limit 结果上限
   * @param timeout 超时时间
   * @return 检索结果
   */
  GrepResult grep(
      CloudPath rootPath,
      String pattern,
      String include,
      boolean ignoreCase,
      boolean literal,
      boolean multiline,
      int limit,
      Duration timeout);

  /** 在指定路径下检索文本内容（RE2/J 或字面量），接受外部 {@link SearchControl}。 */
  GrepResult grep(
      CloudPath rootPath,
      String pattern,
      String include,
      boolean ignoreCase,
      boolean literal,
      boolean multiline,
      int limit,
      SearchControl control)
      throws InterruptedException;

  /**
   * 将权威 UTF-8 文本内容按行窗口切片。
   *
   * @param content 原始文本
   * @param offset 1-based 起始行号
   * @param limit 行数限制
   * @param inlineByteBudget 内联字节预算（达到后提前在整行边界停止）
   * @param maxLineCodePoints 单行最大 code points 上限（超出追加截断标记）
   * @return 文本窗口
   */
  TextWindow windowText(
      String content, int offset, int limit, int inlineByteBudget, int maxLineCodePoints);
}
