package fun.fengwk.kkstudio.platform.cloudfs.service.impl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudArtifactConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeKindConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.repository.CloudNodeRepository;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudArtifactService;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link CloudArtifactService} 实现。 */
@Service
public class CloudArtifactServiceImpl implements CloudArtifactService {

  private static final UUID PRESEEDED_ARTIFACTS_DIR_ID =
      UUID.fromString("c0000000-0000-0000-0000-000000000003");
  private static final UUID PRESEEDED_TOOL_RESULTS_DIR_ID =
      UUID.fromString("c0000000-0000-0000-0000-000000000004");
  private static final String TOOL_RESULTS_DIR_NAME = "tool-results";
  private static final CloudPath TOOL_RESULTS_DIR_PATH = CloudPath.of("/.artifacts/tool-results");

  private final CloudNodeRepository nodeRepository;
  private final ObjectProvider<StorageBlobManager> blobManagerProvider;

  public CloudArtifactServiceImpl(
      CloudNodeRepository nodeRepository, ObjectProvider<StorageBlobManager> blobManagerProvider) {
    this.nodeRepository = Objects.requireNonNull(nodeRepository, "nodeRepository");
    this.blobManagerProvider = Objects.requireNonNull(blobManagerProvider, "blobManagerProvider");
  }

  private StorageBlobManager requireBlobManager() {
    StorageBlobManager mgr = blobManagerProvider.getIfAvailable();
    if (mgr == null) {
      throw new IllegalStateException("StorageBlobManager is not available in current context");
    }
    return mgr;
  }

  @Override
  @Transactional
  public CloudNode createToolArtifact(
      UUID threadId, UUID invocationId, String extension, UUID blobId) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(invocationId, "invocationId");
    Objects.requireNonNull(blobId, "blobId");

    CloudPath path = ToolArtifactPath.format(threadId, invocationId, extension);

    StorageBlob blob = requireBlobManager().getBlob(blobId);
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      throw new StorageResourceNotFoundException("blob", blobId.toString());
    }

    CloudNode toolResultsDir =
        nodeRepository
            .findById(PRESEEDED_TOOL_RESULTS_DIR_ID)
            .orElseThrow(() -> new CloudNodeNotFoundException(TOOL_RESULTS_DIR_PATH));
    if (!toolResultsDir.isDirectory()) {
      throw new CloudNodeKindConflictException(
          TOOL_RESULTS_DIR_PATH, CloudNodeKind.DIRECTORY, toolResultsDir.getKind());
    }
    if (!TOOL_RESULTS_DIR_NAME.equals(toolResultsDir.getName())
        || !PRESEEDED_ARTIFACTS_DIR_ID.equals(toolResultsDir.getParentId())) {
      throw new IllegalStateException(
          "Corrupt artifact storage root invariant: " + TOOL_RESULTS_DIR_PATH);
    }

    String threadDirName = threadId.toString();
    Optional<CloudNode> threadDirOpt =
        nodeRepository.findByParentIdAndName(toolResultsDir.getId(), threadDirName);
    CloudNode threadDir;
    if (threadDirOpt.isEmpty()) {
      UUID newThreadDirId = UUID.randomUUID();
      CloudNode newThreadDir =
          CloudNode.builder()
              .id(newThreadDirId)
              .parentId(toolResultsDir.getId())
              .name(threadDirName)
              .kind(CloudNodeKind.DIRECTORY)
              .version(0L)
              .build();
      boolean inserted = nodeRepository.insertIfAbsent(newThreadDir);
      if (inserted) {
        threadDir = newThreadDir;
      } else {
        threadDir =
            nodeRepository
                .findByParentIdAndName(toolResultsDir.getId(), threadDirName)
                .orElseThrow(() -> new CloudNodeNotFoundException(path.parent()));
      }
    } else {
      threadDir = threadDirOpt.get();
    }

    if (!threadDir.isDirectory()) {
      throw new CloudNodeKindConflictException(
          path.parent(), CloudNodeKind.DIRECTORY, threadDir.getKind());
    }

    Optional<CloudNode> existingOpt =
        nodeRepository.findByParentIdAndName(threadDir.getId(), path.name());
    if (existingOpt.isPresent()) {
      return checkIdempotentReplay(path, existingOpt.get(), blob);
    }

    UUID nodeId = UUID.randomUUID();
    CloudNode artifactNode =
        CloudNode.builder()
            .id(nodeId)
            .parentId(threadDir.getId())
            .name(path.name())
            .kind(CloudNodeKind.BLOB)
            .version(0L)
            .blobId(blobId)
            .build();
    boolean inserted = nodeRepository.insertIfAbsent(artifactNode);
    if (inserted) {
      requireBlobManager().retain(blobId);
      return artifactNode;
    }

    CloudNode racedExisting =
        nodeRepository
            .findByParentIdAndName(threadDir.getId(), path.name())
            .orElseThrow(() -> new CloudNodeNotFoundException(path));
    return checkIdempotentReplay(path, racedExisting, blob);
  }

  private CloudNode checkIdempotentReplay(
      CloudPath path, CloudNode existingNode, StorageBlob blob) {
    if (!existingNode.isBlob()) {
      throw new CloudArtifactConflictException(path, "Existing artifact node is not a BLOB");
    }
    StorageBlob existingBlob = requireBlobManager().getBlob(existingNode.getBlobId());
    if (existingBlob != null
        && existingBlob.getState() == StorageBlobState.ACTIVE
        && existingBlob.getSizeBytes() == blob.getSizeBytes()
        && Objects.equals(existingBlob.getSha256(), blob.getSha256())) {
      return existingNode;
    }

    throw new CloudArtifactConflictException(
        path,
        String.format(
            "Artifact conflict at %s: existing artifact blob content does not match new content",
            path));
  }
}
