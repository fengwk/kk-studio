package fun.fengwk.kkstudio.platform.vfs;

import fun.fengwk.kkstudio.platform.harness.read.PlatformReadException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Platform VFS 目录清单格式化工具。
 *
 * <p>复刻与 daemon {@code fs.read} 目录读取完全一致的有界输出格式：
 *
 * <ul>
 *   <li>Header：{@code path: <displayPath>} 与 {@code kind: directory}；
 *   <li>条目列表：按字母升序排列，带 {@code (read-only)} 或 {@code (read-write)} 标记；
 *   <li>分页约束：{@code offset} 与 {@code limit}；
 *   <li>未完整展示时附带尾部提示：{@code [Showing entries X-Y of Z. Re-run read with offset=N to continue.]}。
 * </ul>
 */
public final class VfsDirectoryListing {

  public static final int DEFAULT_LIMIT = 2000;
  public static final int MAX_LIMIT = 2000;
  public static final int MAX_DIRECTORY_RESPONSE_BYTES = 48 * 1024;

  private VfsDirectoryListing() {}

  public static String format(
      String displayPath, List<String> entries, Integer offset, Integer limit) {
    Objects.requireNonNull(displayPath, "displayPath");
    Objects.requireNonNull(entries, "entries");

    if (offset != null && offset < 1) {
      throw new PlatformReadException("offset must be a positive integer");
    }
    if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
      throw new PlatformReadException("limit must be a positive integer <= " + MAX_LIMIT);
    }

    int resolvedOffset = offset == null ? 1 : offset;
    int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
    int totalEntries = entries.size();

    List<String> headers = List.of("path: " + displayPath, "kind: directory", "");

    if (resolvedOffset > totalEntries) {
      List<String> out = new ArrayList<>(headers);
      out.add("[Showing 0 entries of " + totalEntries + ".]");
      return String.join("\n", out);
    }

    int start = Math.min(resolvedOffset, totalEntries + 1);
    int end = Math.min(totalEntries, start + resolvedLimit - 1);

    int bytes = responseUtf8Bytes(headers);
    List<String> outputEntries = new ArrayList<>();
    int actualEnd = start - 1;

    for (int index = start; index <= end; index++) {
      String entryName = entries.get(index - 1);
      int entryBytes = utf8Bytes(entryName);
      int candidateBytes = bytes + 1 + entryBytes;
      if (index < totalEntries) {
        candidateBytes += 2 + utf8Bytes(directoryTailLine(start, index, totalEntries));
      }
      if (candidateBytes > MAX_DIRECTORY_RESPONSE_BYTES) {
        if (index == start) {
          throw new PlatformReadException(
              "directory read response exceeds "
                  + MAX_DIRECTORY_RESPONSE_BYTES
                  + " bytes on first entry");
        }
        break;
      }
      outputEntries.add(entryName);
      bytes += 1 + entryBytes;
      actualEnd = index;
    }

    List<String> output = new ArrayList<>(headers);
    output.addAll(outputEntries);
    if (actualEnd < totalEntries && start <= totalEntries) {
      output.add("");
      output.add(directoryTailLine(start, actualEnd, totalEntries));
    }

    return String.join("\n", output);
  }

  private static String directoryTailLine(int start, int end, int total) {
    return "[Showing entries "
        + start
        + "-"
        + end
        + " of "
        + total
        + ". Re-run read with offset="
        + (end + 1)
        + " to continue.]";
  }

  private static int utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static int responseUtf8Bytes(List<String> lines) {
    if (lines.isEmpty()) {
      return 0;
    }
    int bytes = lines.size() - 1;
    for (String line : lines) {
      bytes += utf8Bytes(line);
    }
    return bytes;
  }
}
