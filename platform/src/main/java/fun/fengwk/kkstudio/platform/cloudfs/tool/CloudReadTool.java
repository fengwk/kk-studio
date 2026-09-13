package fun.fengwk.kkstudio.platform.cloudfs.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.builtin.CompletedToolExecutionHandle;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.platform.cloudfs.blob.BlobReadResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code cloud_read} 工具实现。
 *
 * <p>读取 Cloud File System 中的目录、文本文件、BLOB 以及 Tool Artifact。
 */
public final class CloudReadTool implements Tool {

  public static final String NAME = "cloud_read";
  public static final String VERSION = "1";
  public static final int DEFAULT_LIMIT = 200;
  public static final int MAX_LIMIT = 2000;
  public static final int MAX_LINE_CODE_POINTS = 2000;
  public static final int INLINE_BYTE_BUDGET = 40 * 1024; // 控制在 40 KiB 内联预算内（严格小于 50 KiB）

  private static final ToolDescriptor DESCRIPTOR =
      new ToolDescriptor(
          NAME,
          VERSION,
          CloudToolPrompts.prompt(NAME),
          NAME,
          CloudToolPrompts.schema(NAME),
          ToolSideEffect.READ_ONLY,
          Duration.ofMinutes(1));

  private final CloudFileSystemService fileSystemService;
  private final StorageBlobFileReader blobFileReader;
  private final CloudQueryService queryService;

  public CloudReadTool(
      CloudFileSystemService fileSystemService,
      StorageBlobFileReader blobFileReader,
      CloudQueryService queryService) {
    this.fileSystemService = Objects.requireNonNull(fileSystemService, "fileSystemService");
    this.blobFileReader = blobFileReader;
    this.queryService = Objects.requireNonNull(queryService, "queryService");
  }

  @Override
  public ToolDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");

    try {
      JsonNode args = CloudToolArguments.parse(request.call().argumentsJson());
      String rawPath = CloudToolArguments.requireString(args, "path");
      int offset = CloudToolArguments.optionalBoundedInt(args, "offset", 1, 1, Integer.MAX_VALUE);
      int limit = CloudToolArguments.optionalBoundedInt(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

      CloudPath path = CloudPath.of(rawPath);
      ToolResult result;

      if (path.isArtifactPath()) {
        if (!ToolArtifactPath.isToolArtifactPath(path)) {
          throw new CloudPathForbiddenException(
              path, "Access to artifact directory or non-canonical artifact path is forbidden");
        }
        result = readArtifact(request.call().id(), path, offset, limit);
      } else {
        result = readUserNode(request.call().id(), path, offset, limit);
      }

      listener.onComplete(ToolOutcome.withoutEffects(result));
    } catch (CloudFileSystemException | IllegalArgumentException e) {
      ToolResult errorResult =
          new ToolResult(
              request.call().id(), List.of(new TextResultContent(e.getMessage())), true, "{}");
      listener.onComplete(ToolOutcome.withoutEffects(errorResult));
    } catch (Exception e) {
      listener.onError(e);
    }
    return CompletedToolExecutionHandle.INSTANCE;
  }

  private ToolResult readArtifact(String callId, CloudPath path, int offset, int limit) {
    CloudNode node = fileSystemService.getNode(path);
    if (!node.isBlob() || node.getBlobId() == null) {
      throw new IllegalStateException("Tool artifact node is not a valid blob: " + path);
    }
    if (blobFileReader == null) {
      throw new IllegalStateException(
          "Blob content reader is not available in current environment");
    }

    BlobReadResult readResult = blobFileReader.read(node.getBlobId());
    if (readResult instanceof BlobReadResult.Text textBlob) {
      String formatted =
          formatTextWindow(path.value(), "text", 1, textBlob.text(), offset, limit, true);
      return new ToolResult(callId, List.of(new TextResultContent(formatted)), false, "{}");
    }
    throw new IllegalArgumentException("Tool artifact is not valid UTF-8 text: " + path);
  }

  private ToolResult readUserNode(String callId, CloudPath path, int offset, int limit) {
    CloudNode node = fileSystemService.getNode(path);
    if (node.isDirectory()) {
      List<CloudNode> children = fileSystemService.listChildren(path);
      String text = formatDirectory(path.value(), children, offset, limit);
      return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
    }

    if (node.isText()) {
      CloudTextRevision current = fileSystemService.readCurrentText(path);
      String text =
          formatTextWindow(
              path.value(),
              "text",
              current.getRevision(),
              current.getContent(),
              offset,
              limit,
              false);
      return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
    }

    // node.isBlob()
    if (blobFileReader == null) {
      throw new IllegalStateException(
          "Blob content reader is not available in current environment");
    }
    BlobReadResult readResult = blobFileReader.read(node.getBlobId());
    if (readResult instanceof BlobReadResult.Text textBlob) {
      String text =
          formatTextWindow(path.value(), "blob", 0, textBlob.text(), offset, limit, false);
      return new ToolResult(callId, List.of(new TextResultContent(text)), false, "{}");
    }

    BlobReadResult.Binary binary = (BlobReadResult.Binary) readResult;
    String metadata =
        "path: "
            + path.value()
            + "\nkind: blob\nmedia_type: "
            + binary.mediaType()
            + "\nsize_bytes: "
            + binary.sizeBytes();
    List<ResultContent> contents =
        List.of(
            new TextResultContent(metadata),
            new BinaryResultContent(binary.mediaType(), binary.bytes()));
    return new ToolResult(callId, contents, false, "{}");
  }

  private String formatDirectory(String pathStr, List<CloudNode> children, int offset, int limit) {
    int total = children.size();
    int start = Math.min(offset, total + 1);
    int end = Math.min(total, start + limit - 1);

    List<String> headerLines = new ArrayList<>();
    headerLines.add("path: " + pathStr);
    headerLines.add("kind: directory");
    headerLines.add("");

    if (total == 0) {
      return String.join("\n", headerLines);
    }

    if (offset > total) {
      headerLines.add(
          String.format(
              "[Showing entries 0-0 of %d. Re-run cloud_read with offset=1 to continue.]", total));
      return String.join("\n", headerLines);
    }

    List<String> entryLines = new ArrayList<>();
    int currentBytes = String.join("\n", headerLines).getBytes(StandardCharsets.UTF_8).length + 1;
    int maxContentBytes = INLINE_BYTE_BUDGET - 256; // 预留页尾说明
    int actualEnd = start - 1;

    for (int i = start; i <= end; i++) {
      CloudNode child = children.get(i - 1);
      String entry = child.getName() + (child.isDirectory() ? "/" : "");
      byte[] entryBytes = entry.getBytes(StandardCharsets.UTF_8);

      if (i > start && currentBytes + entryBytes.length + 1 > maxContentBytes) {
        break;
      }

      entryLines.add(entry);
      currentBytes += entryBytes.length + 1;
      actualEnd = i;
    }

    List<String> output = new ArrayList<>(headerLines);
    output.addAll(entryLines);

    if (actualEnd < total) {
      output.add("");
      output.add(
          String.format(
              "[Showing entries %d-%d of %d. Re-run cloud_read with offset=%d to continue.]",
              start, actualEnd, total, actualEnd + 1));
    }

    return String.join("\n", output);
  }

  private String formatTextWindow(
      String pathStr,
      String kind,
      long revision,
      String content,
      int offset,
      int limit,
      boolean isArtifact) {
    CloudQueryService.TextWindow window =
        queryService.windowText(content, offset, limit, INLINE_BYTE_BUDGET, MAX_LINE_CODE_POINTS);

    List<String> output = new ArrayList<>();
    output.add("path: " + pathStr);
    output.add("kind: " + kind);
    if ("text".equals(kind) && !isArtifact) {
      output.add("revision: " + revision);
    }
    output.add("ends_with_newline: " + (window.endsWithNewline() ? "yes" : "no"));
    output.add("");

    if (offset > window.totalLines()) {
      output.add(
          String.format(
              "[Showing lines 0-0 of %d. Re-run cloud_read with offset=1 to continue.]",
              window.totalLines()));
      return String.join("\n", output);
    }

    int width = Math.max(1, Integer.toString(Math.max(1, window.totalLines())).length());
    int currentBytes = String.join("\n", output).getBytes(StandardCharsets.UTF_8).length + 1;
    int maxContentBytes = INLINE_BYTE_BUDGET - 256; // 预留页尾说明字节
    int actualEnd = offset - 1;
    boolean hasTruncatedLine = false;

    for (CloudQueryService.TextLine line : window.lines()) {
      if (line.truncated()) {
        hasTruncatedLine = true;
      }
      String prefix = String.format("%" + width + "d|", line.lineNumber());
      String formattedLine = prefix + line.content();
      byte[] lineBytes = formattedLine.getBytes(StandardCharsets.UTF_8);

      if (line.lineNumber() > offset && currentBytes + lineBytes.length + 1 > maxContentBytes) {
        // 达到内联字节预算，在完整行边界提前停止
        break;
      }

      output.add(formattedLine);
      currentBytes += lineBytes.length + 1;
      actualEnd = line.lineNumber();

      if (currentBytes >= maxContentBytes) {
        break;
      }
    }

    if (hasTruncatedLine) {
      output.add("");
      output.add("[Note: one or more lines were truncated to fit length or inline byte limits.]");
    }

    if (actualEnd < window.totalLines()) {
      output.add("");
      output.add(
          String.format(
              "[Showing lines %d-%d of %d. Re-run cloud_read with offset=%d to continue.]",
              offset, actualEnd, window.totalLines(), actualEnd + 1));
    }

    return String.join("\n", output);
  }
}
