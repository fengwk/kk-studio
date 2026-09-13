package fun.fengwk.kkstudio.web.cloudfs;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudQueryService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.share.cloudfs.CloudBlobMetadataDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudFindResultDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudGrepMatchDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudGrepResultDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudNodeDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudTextLineDTO;
import fun.fengwk.kkstudio.share.cloudfs.CloudTextWindowDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Cloud File System Web 层 DTO 映射器。 */
public final class CloudFilesDtoMapper {

  private CloudFilesDtoMapper() {}

  public static CloudNodeDTO toNodeDTO(CloudNode node, CloudPath path) {
    return toNodeDTO(node, path, null, null);
  }

  public static CloudNodeDTO toTextNodeDTO(
      CloudNode node, CloudPath path, CloudTextRevision revision) {
    return toNodeDTO(node, path, Objects.requireNonNull(revision, "revision"), null);
  }

  public static CloudNodeDTO toBlobNodeDTO(CloudNode node, CloudPath path, StorageBlob blob) {
    return toNodeDTO(node, path, null, blob);
  }

  private static CloudNodeDTO toNodeDTO(
      CloudNode node, CloudPath path, CloudTextRevision revision, StorageBlob blob) {
    if (node == null) {
      return null;
    }
    Objects.requireNonNull(path, "path");
    return CloudNodeDTO.builder()
        .id(node.getId() != null ? node.getId().toString() : null)
        .path(path.value())
        .name(node.getName())
        .kind(node.getKind() != null ? node.getKind().name() : null)
        .version(String.valueOf(node.getVersion()))
        .blobId(node.getBlobId() != null ? node.getBlobId().toString() : null)
        .mediaType(
            revision != null
                ? "text/plain; charset=utf-8"
                : blob != null ? blob.getMediaType() : null)
        .sizeBytes(
            revision != null
                ? String.valueOf(revision.getSizeBytes())
                : blob != null ? String.valueOf(blob.getSizeBytes()) : null)
        .sha256(revision != null ? revision.getSha256() : blob != null ? blob.getSha256() : null)
        .revision(revision != null ? String.valueOf(revision.getRevision()) : null)
        .createdAt(node.getCreatedAt() != null ? node.getCreatedAt().toString() : null)
        .updatedAt(node.getUpdatedAt() != null ? node.getUpdatedAt().toString() : null)
        .build();
  }

  public static CloudTextWindowDTO toTextWindowDTO(CloudQueryService.TextWindow window) {
    return toTextWindowDTO(window, null);
  }

  public static CloudTextWindowDTO toTextWindowDTO(
      CloudQueryService.TextWindow window, Long revision) {
    if (window == null) {
      return null;
    }
    List<CloudTextLineDTO> lines = new ArrayList<>();
    if (window.lines() != null) {
      for (CloudQueryService.TextLine line : window.lines()) {
        lines.add(
            CloudTextLineDTO.builder()
                .lineNumber(line.lineNumber())
                .content(line.content())
                .truncated(line.truncated())
                .build());
      }
    }
    return CloudTextWindowDTO.builder()
        .revision(revision != null ? String.valueOf(revision) : null)
        .offset(window.offset())
        .limit(window.limit())
        .totalLines(window.totalLines())
        .nextOffset(window.nextOffset())
        .endsWithNewline(window.endsWithNewline())
        .lines(lines)
        .content(window.content())
        .build();
  }

  public static CloudBlobMetadataDTO toBlobMetadataDTO(
      UUID blobId, StorageBlob blob, CloudQueryService.TextWindow textWindow) {
    Objects.requireNonNull(blobId, "blobId");
    return CloudBlobMetadataDTO.builder()
        .blobId(blobId.toString())
        .sizeBytes(blob != null ? String.valueOf(blob.getSizeBytes()) : null)
        .sha256(blob != null ? blob.getSha256() : null)
        .mediaType(blob != null ? blob.getMediaType() : null)
        .createdAt(
            blob != null && blob.getCreateTime() != null ? blob.getCreateTime().toString() : null)
        .text(toTextWindowDTO(textWindow))
        .build();
  }

  public static CloudFindResultDTO toFindResultDTO(CloudQueryService.FindResult findResult) {
    Objects.requireNonNull(findResult, "findResult");
    return CloudFindResultDTO.builder()
        .paths(new ArrayList<>(findResult.paths()))
        .limited(findResult.limited())
        .build();
  }

  public static CloudGrepResultDTO toGrepResultDTO(CloudQueryService.GrepResult grepResult) {
    Objects.requireNonNull(grepResult, "grepResult");
    List<CloudGrepMatchDTO> matches = new ArrayList<>();
    if (grepResult.matches() != null) {
      for (CloudQueryService.GrepMatch m : grepResult.matches()) {
        matches.add(
            CloudGrepMatchDTO.builder()
                .path(m.path().value())
                .lineNumber(m.lineNumber())
                .content(m.content())
                .build());
      }
    }
    return CloudGrepResultDTO.builder().matches(matches).limited(grepResult.limited()).build();
  }
}
