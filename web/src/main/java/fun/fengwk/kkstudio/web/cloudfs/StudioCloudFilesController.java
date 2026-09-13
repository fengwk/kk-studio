package fun.fengwk.kkstudio.web.cloudfs;

import static fun.fengwk.kkstudio.web.cloudfs.CloudFilesDtoMapper.toBlobMetadataDTO;
import static fun.fengwk.kkstudio.web.cloudfs.CloudFilesDtoMapper.toBlobNodeDTO;
import static fun.fengwk.kkstudio.web.cloudfs.CloudFilesDtoMapper.toFindResultDTO;
import static fun.fengwk.kkstudio.web.cloudfs.CloudFilesDtoMapper.toGrepResultDTO;
import static fun.fengwk.kkstudio.web.cloudfs.CloudFilesDtoMapper.toNodeDTO;
import static fun.fengwk.kkstudio.web.cloudfs.CloudFilesDtoMapper.toTextNodeDTO;
import static fun.fengwk.kkstudio.web.cloudfs.CloudFilesDtoMapper.toTextWindowDTO;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.cloudfs.blob.BlobReadResult;
import fun.fengwk.kkstudio.platform.cloudfs.blob.StorageBlobFileReader;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.cloudfs.CloudBlobMountRequestDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudDirectoryCreateRequestDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudFileSnapshotDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudFindRequestDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudFindResultDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudGrepRequestDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudGrepResultDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudNodeDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudNodeMoveRequestDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudTextEditRequestDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudTextWriteRequestDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Cloud File System REST 控制器。
 *
 * <p>提供规范的文件树浏览、目录管理、文本写入/编辑（CAS 保护）、节点移动/删除、Blob 挂载以及可复用查找/检索能力。
 */
@Validated
@RestController
@RequestMapping("/api/cloud")
public class StudioCloudFilesController {

  private static final int INLINE_BYTE_BUDGET = 50 * 1024;
  private static final int MAX_LINE_CODE_POINTS = 2000;

  private final CloudFileSystemService fileSystemService;
  private final CloudQueryService queryService;
  private final ObjectProvider<StorageBlobManager> blobManagerProvider;
  private final ObjectProvider<StorageBlobFileReader> blobFileReaderProvider;

  public StudioCloudFilesController(
      CloudFileSystemService fileSystemService,
      CloudQueryService queryService,
      ObjectProvider<StorageBlobManager> blobManagerProvider,
      ObjectProvider<StorageBlobFileReader> blobFileReaderProvider) {
    this.fileSystemService = Objects.requireNonNull(fileSystemService, "fileSystemService");
    this.queryService = Objects.requireNonNull(queryService, "queryService");
    this.blobManagerProvider = Objects.requireNonNull(blobManagerProvider, "blobManagerProvider");
    this.blobFileReaderProvider =
        Objects.requireNonNull(blobFileReaderProvider, "blobFileReaderProvider");
  }

  @GetMapping("/files")
  public Result<CloudFileSnapshotDTO> getFileSnapshot(
      @RequestParam("path") String path,
      @RequestParam(value = "offset", defaultValue = "1") int offset,
      @RequestParam(value = "limit", defaultValue = "200") int limit) {
    if (offset < 1) {
      throw new IllegalArgumentException("offset must be positive: " + offset);
    }
    if (limit < 1 || limit > 2000) {
      throw new IllegalArgumentException("limit must be between 1 and 2000: " + limit);
    }

    CloudPath cloudPath = requirePath(path, "path");
    if (cloudPath.isArtifactPath()) {
      if (!ToolArtifactPath.isToolArtifactPath(cloudPath)) {
        throw new CloudPathForbiddenException(
            cloudPath, "Access to non-canonical artifact path is forbidden: " + cloudPath);
      }
    }

    CloudNode node = fileSystemService.getNode(cloudPath);
    if (node.isDirectory()) {
      List<CloudNode> allChildren = fileSystemService.listChildren(cloudPath);
      List<CloudNodeDTO> childrenDTOs = new ArrayList<>();
      for (CloudNode child : allChildren) {
        if (cloudPath.isRoot() && ".artifacts".equals(child.getName())) {
          continue;
        }
        childrenDTOs.add(toNodeDTO(child, childPath(cloudPath, child)));
      }
      return Results.ok(
          CloudFileSnapshotDTO.builder()
              .node(toNodeDTO(node, cloudPath))
              .children(childrenDTOs)
              .text(null)
              .blob(null)
              .build());
    } else if (node.isText()) {
      CloudTextRevision rev = fileSystemService.readCurrentText(cloudPath);
      CloudQueryService.TextWindow window =
          queryService.windowText(
              rev.getContent(), offset, limit, INLINE_BYTE_BUDGET, MAX_LINE_CODE_POINTS);
      return Results.ok(
          CloudFileSnapshotDTO.builder()
              .node(toTextNodeDTO(node, cloudPath, rev))
              .children(null)
              .text(toTextWindowDTO(window, rev.getRevision()))
              .blob(null)
              .build());
    } else if (node.isBlob()) {
      return Results.ok(blobSnapshot(cloudPath, node, offset, limit));
    }

    throw new IllegalStateException("Unknown node kind: " + node.getKind());
  }

  @PostMapping("/directories")
  public Result<CloudNodeDTO> createDirectory(
      @Valid @RequestBody CloudDirectoryCreateRequestDTO request) {
    CloudPath path = requirePath(request.getPath(), "path");
    CloudNode dir = fileSystemService.mkdir(path, Boolean.TRUE.equals(request.getRecursive()));
    return Results.ok(toNodeDTO(dir, path));
  }

  @PutMapping("/text")
  public Result<CloudNodeDTO> writeText(@Valid @RequestBody CloudTextWriteRequestDTO request) {
    CloudPath path = requirePath(request.getPath(), "path");
    long expectedRevision = parseNonNegativeLong(request.getExpectedRevision(), "expectedRevision");
    String content = requireString(request.getContent(), "content");
    CloudTextRevision revision = fileSystemService.writeText(path, content, expectedRevision);
    CloudNode node = fileSystemService.getNode(path);
    return Results.ok(toTextNodeDTO(node, path, revision));
  }

  @PatchMapping("/text")
  public Result<CloudNodeDTO> editText(@Valid @RequestBody CloudTextEditRequestDTO request) {
    CloudPath path = requirePath(request.getPath(), "path");
    long expectedRevision = parsePositiveLong(request.getExpectedRevision(), "expectedRevision");
    CloudTextRevision revision =
        fileSystemService.editText(
            path,
            requireString(request.getOldString(), "oldString"),
            requireString(request.getNewString(), "newString"),
            expectedRevision,
            Boolean.TRUE.equals(request.getReplaceAll()));
    CloudNode node = fileSystemService.getNode(path);
    return Results.ok(toTextNodeDTO(node, path, revision));
  }

  @PostMapping("/nodes/move")
  public Result<CloudNodeDTO> moveNode(@Valid @RequestBody CloudNodeMoveRequestDTO request) {
    CloudPath source = requirePath(request.getSourcePath(), "sourcePath");
    CloudPath target = requirePath(request.getDestinationPath(), "destinationPath");
    long expectedVersion = parseNonNegativeLong(request.getExpectedVersion(), "expectedVersion");
    CloudNode moved = fileSystemService.moveNode(source, target, expectedVersion);
    return Results.ok(toNodeDTO(moved, target));
  }

  @DeleteMapping("/nodes")
  public Result<Void> deleteNode(
      @RequestParam("path") String path, @RequestParam("expectedVersion") String expectedVersion) {
    long expVer = parseNonNegativeLong(expectedVersion, "expectedVersion");
    fileSystemService.deleteNode(requirePath(path, "path"), expVer);
    return Results.noContent();
  }

  @PostMapping("/blobs")
  public Result<CloudNodeDTO> mountBlob(@Valid @RequestBody CloudBlobMountRequestDTO request) {
    if (!Boolean.TRUE.equals(request.getExpectedAbsent())) {
      throw new IllegalArgumentException("expectedAbsent must be true");
    }
    UUID uploadId = parseUuid(request.getUploadId(), "uploadId");
    CloudPath path = requirePath(request.getPath(), "path");
    CloudNode mounted = fileSystemService.attachBlobUpload(path, uploadId, true);
    StorageBlob blob = getBlob(mounted.getBlobId());
    return Results.ok(toBlobNodeDTO(mounted, path, blob));
  }

  @PostMapping("/find")
  public Result<CloudFindResultDTO> findFiles(@Valid @RequestBody CloudFindRequestDTO request) {
    CloudPath rootPath = requirePath(request.getPath(), "path");
    String pattern = requireString(request.getPattern(), "pattern");
    int limit = request.getLimit() != null ? request.getLimit() : 200;
    Duration timeout = parseTimeout(request.getTimeoutSeconds());
    CloudQueryService.FindResult findResult = queryService.find(rootPath, pattern, limit, timeout);
    return Results.ok(toFindResultDTO(findResult));
  }

  @PostMapping("/grep")
  public Result<CloudGrepResultDTO> grepFiles(@Valid @RequestBody CloudGrepRequestDTO request) {
    CloudPath rootPath = requirePath(request.getPath(), "path");
    String pattern = requireString(request.getPattern(), "pattern");
    int limit = request.getLimit() != null ? request.getLimit() : 100;
    Duration timeout = parseTimeout(request.getTimeoutSeconds());
    CloudQueryService.GrepResult grepResult =
        queryService.grep(
            rootPath,
            pattern,
            request.getInclude(),
            Boolean.TRUE.equals(request.getIgnoreCase()),
            Boolean.TRUE.equals(request.getLiteral()),
            Boolean.TRUE.equals(request.getMultiline()),
            limit,
            timeout);
    return Results.ok(toGrepResultDTO(grepResult));
  }

  private CloudFileSnapshotDTO blobSnapshot(CloudPath path, CloudNode node, int offset, int limit) {
    StorageBlob blob = getBlob(node.getBlobId());
    CloudQueryService.TextWindow textWindow = null;
    StorageBlobFileReader reader = blobFileReaderProvider.getIfAvailable();
    if (blob != null
        && reader != null
        && StorageBlobFileReader.isTextMediaType(blob.getMediaType())) {
      BlobReadResult readResult = reader.read(node.getBlobId());
      if (readResult instanceof BlobReadResult.Text text) {
        textWindow =
            queryService.windowText(
                text.text(), offset, limit, INLINE_BYTE_BUDGET, MAX_LINE_CODE_POINTS);
      }
    }
    return CloudFileSnapshotDTO.builder()
        .node(toBlobNodeDTO(node, path, blob))
        .children(null)
        .text(null)
        .blob(toBlobMetadataDTO(node.getBlobId(), blob, textWindow))
        .build();
  }

  private StorageBlob getBlob(UUID blobId) {
    StorageBlobManager blobManager = blobManagerProvider.getIfAvailable();
    return blobManager != null ? blobManager.getBlob(blobId) : null;
  }

  private static CloudPath childPath(CloudPath parent, CloudNode child) {
    String value = parent.isRoot() ? "/" + child.getName() : parent.value() + "/" + child.getName();
    return CloudPath.of(value);
  }

  private static CloudPath requirePath(String value, String field) {
    return CloudPath.of(requireString(value, field));
  }

  private static String requireString(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static long parsePositiveLong(String value, String field) {
    long parsed = parseNonNegativeLong(value, field);
    if (parsed == 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return parsed;
  }

  private static long parseNonNegativeLong(String value, String field) {
    if (value == null || !value.matches("0|[1-9]\\d*")) {
      throw new IllegalArgumentException(field + " must be a non-negative decimal string");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(field + " is out of range");
    }
  }

  private static UUID parseUuid(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value)) {
        throw new IllegalArgumentException(field + " must be a canonical UUID");
      }
      return parsed;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(field + " must be a canonical UUID");
    }
  }

  private static Duration parseTimeout(Integer timeoutSeconds) {
    int seconds = timeoutSeconds != null ? timeoutSeconds : 15;
    if (seconds < 1 || seconds > 3600) {
      throw new IllegalArgumentException("timeoutSeconds must be between 1 and 3600");
    }
    return Duration.ofSeconds(seconds);
  }
}
