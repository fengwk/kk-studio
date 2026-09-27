package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 读取有界文本窗口或确定性的目录清单。
 *
 * <p>文本读取不按整文件分配内存：媒体类型只由文件前缀判定；正文由共享核心 {@link
 * fun.fengwk.kkstudio.harness.common.text.TextReadWindow} 单遍流式扫描（本地侧经 {@link LocalTextReadWindow}
 * 适配），只保留窗口内的起始行片段与后续行内容，因此 Daemon 自己或外部工具生成的超大文本（包括 {@code process.exec} 落盘的全文）都可以被分页读取，不存在“文本文件超过
 * N MiB 就拒绝”或“单行超过 N 码点就截断”的限制。窗口契约（{@code offset}/{@code column_offset} 从 1 开始、{@code limit} 默认且最大
 * 2000、正文最多 60000 个 Unicode 码点、{@code range}/截断元数据）由该核心定义。
 *
 * <p>图片仍是 Resource 语义：探测到受支持的图片签名时，整文件字节作为内联 {@link BinaryResultContent} 返回，由终态编码阶段直传全局对象
 * 存储。二进制判定同样来自流式解码：非法 UTF-8 序列、NUL 字符或非法代理项立即以明确的“看似二进制文件”失败。
 *
 * <p>目录保持独立的返回语义，同样使用 {@code offset}/{@code limit} 分页，{@code limit} 默认与上限都是 2000。
 */
public final class ReadCapability extends AbstractCodingCapability {

  private static final int DEFAULT_LIMIT = LocalTextReadWindow.DEFAULT_LIMIT;
  private static final int MAX_LIMIT = LocalTextReadWindow.MAX_LIMIT;

  /** 目录清单的展示上界：目录不是分页正文，仍以字节上界约束单次响应的展示体积。 */
  private static final int MAX_DIRECTORY_RESPONSE_BYTES = 48 * 1024;

  public ReadCapability(CodingToolsConfig config, ExecutorService executor) {
    super(config, executor, EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.FS_READ));
  }

  @Override
  EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception {
    JsonNode args = arguments(request);
    String rawPath = string(args, "path");
    String rawWorkdir = optionalString(args, "workdir");
    // 窗口参数先于任何文件系统访问校验，畸形窗口不会以 ENOENT 之类的 I/O 结论掩盖参数错误。
    int offset = optionalPositiveInt(args, "offset", 1, Integer.MAX_VALUE);
    int limit = optionalPositiveInt(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);
    Integer columnOffset = parseOptionalPositiveInt(args, "column_offset");
    Path workdir = rawWorkdir == null ? null : EnvironmentPaths.workdir(rawWorkdir);
    Path path = EnvironmentPaths.existing(rawPath, workdir);
    String displayPath = EnvironmentPaths.displayPath(path, workdir, rawPath);

    if (Files.isDirectory(path)) {
      if (columnOffset != null) {
        throw new IllegalArgumentException("column_offset is only supported for text files");
      }
      return directoryResponse(request, args, path, displayPath, offset, limit);
    }

    if (!Files.isRegularFile(path)) {
      // 字符设备、FIFO、socket、块设备等非普通节点必须在任何 I/O 之前拒绝：否则 probe/阅读可能阻塞或给出伪造空文本。
      throw new IllegalArgumentException("not a regular file: " + displayPath);
    }

    byte[] probe = TextStreams.probe(path);

    String imageMime = detectImageMediaType(probe);
    if (imageMime != null) {
      if (columnOffset != null) {
        throw new IllegalArgumentException("column_offset is only supported for text files");
      }
      byte[] bytes = Files.readAllBytes(path);
      // 图片字节不落本地：终态编码阶段直接上传全局对象存储，wire 上只出现上传引用。
      return EnvironmentCapabilityResult.binary(request.call().id(), bytes, imageMime);
    }

    TextStreams.Encoding encoding = TextStreams.detectEncoding(probe);
    if (encoding.looksBinary(probe)) {
      throw new IllegalArgumentException("file appears to be binary");
    }

    String text =
        LocalTextReadWindow.read(
            path, encoding, offset, limit, columnOffset, displayPath, lspStatus(path));
    return EnvironmentCapabilityResult.text(request.call().id(), text);
  }

  private String lspStatus(Path path) {
    LspSupport support = config.lsp().support(path);
    return support.available() ? "supported (" + support.language().orElseThrow() + ")" : null;
  }

  private EnvironmentCapabilityResult directoryResponse(
      EnvironmentCapabilityExecutionRequest request,
      JsonNode args,
      Path path,
      String displayPath,
      int offset,
      int limit)
      throws Exception {
    List<String> names;
    try (var entries = Files.list(path)) {
      names =
          entries
              .map(ReadCapability::directoryEntryName)
              .sorted(Comparator.naturalOrder())
              .toList();
    }
    int totalEntries = names.size();
    int start = Math.min(offset, totalEntries + 1);
    int end = Math.min(totalEntries, start + limit - 1);

    List<String> headers = List.of("path: " + displayPath, "kind: directory", "");
    String callId = request.call().id();
    if (offset > totalEntries) {
      List<String> output = new ArrayList<>(headers);
      output.add("[Showing 0 entries of " + totalEntries + ".]");
      return textResponse(callId, String.join("\n", output));
    }

    // 逐条累加候选体积而不是每条重新拼接整个清单：目录上限已提升到 2000 条，重复拼接会让最坏情况退化为 O(limit^2)。
    int actualEnd = start - 1;
    List<String> entriesList = new ArrayList<>();
    int bytes = responseUtf8Bytes(headers);
    for (int index = start; index <= end; index++) {
      String entryName = names.get(index - 1);
      int entryBytes = utf8Bytes(entryName);
      int candidateBytes = bytes + 1 + entryBytes;
      if (index < totalEntries) {
        candidateBytes += 2 + utf8Bytes(directoryTailLine(start, index, totalEntries));
      }
      if (candidateBytes > MAX_DIRECTORY_RESPONSE_BYTES) {
        if (index == start) {
          throw new IllegalStateException(
              "directory read response exceeds "
                  + MAX_DIRECTORY_RESPONSE_BYTES
                  + " bytes on first entry: "
                  + candidateBytes
                  + " bytes");
        }
        break;
      }
      entriesList.add(entryName);
      bytes += 1 + entryBytes;
      actualEnd = index;
    }

    List<String> output = new ArrayList<>(headers);
    output.addAll(entriesList);
    if (actualEnd < totalEntries && start <= totalEntries) {
      output.add("");
      output.add(directoryTailLine(start, actualEnd, totalEntries));
    }
    return textResponse(callId, String.join("\n", output));
  }

  private static String directoryTailLine(int start, int end, int totalEntries) {
    return "[Showing entries "
        + start
        + "-"
        + end
        + " of "
        + totalEntries
        + ". Re-run read with offset="
        + (end + 1)
        + " to continue.]";
  }

  private static int utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  static String detectImageMediaType(byte[] bytes) {
    if (bytes == null || bytes.length < 3) {
      return null;
    }
    if (bytes.length >= 8
        && (bytes[0] & 0xFF) == 0x89
        && bytes[1] == 'P'
        && bytes[2] == 'N'
        && bytes[3] == 'G'
        && bytes[4] == 0x0D
        && bytes[5] == 0x0A
        && bytes[6] == 0x1A
        && bytes[7] == 0x0A) {
      return "image/png";
    }
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (bytes.length >= 6
        && bytes[0] == 'G'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == '8'
        && (bytes[4] == '7' || bytes[4] == '9')
        && bytes[5] == 'a') {
      return "image/gif";
    }
    if (bytes.length >= 12
        && bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'E'
        && bytes[10] == 'B'
        && bytes[11] == 'P') {
      return "image/webp";
    }
    return null;
  }

  private static Integer parseOptionalPositiveInt(JsonNode args, String name) {
    JsonNode value = args.get(name);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isInt() || value.intValue() < 1) {
      throw new IllegalArgumentException(name + " must be a positive integer");
    }
    return value.intValue();
  }

  private static String directoryEntryName(Path entry) {
    Path fileName = entry.getFileName();
    String name = fileName == null ? entry.toString() : fileName.toString();
    return name + (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? "/" : "");
  }

  /**
   * 目录清单的 48 KiB 有界响应：目录维持独立的展示语义，只有文本正文改用共享核心的 60000 码点预算。
   *
   * <p>该 48 KiB 是目录清单的体积防线，与文本正文无关。
   */
  static EnvironmentCapabilityResult textResponse(String callId, String text) {
    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_DIRECTORY_RESPONSE_BYTES) {
      throw new IllegalStateException(
          "read response exceeds "
              + MAX_DIRECTORY_RESPONSE_BYTES
              + " bytes invariant: "
              + bytes
              + " bytes");
    }
    return EnvironmentCapabilityResult.text(callId, text);
  }

  private static int responseUtf8Bytes(List<String> lines) {
    if (lines.isEmpty()) {
      return 0;
    }
    int bytes = lines.size() - 1;
    for (String line : lines) {
      bytes += line.getBytes(StandardCharsets.UTF_8).length;
    }
    return bytes;
  }
}
