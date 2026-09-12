package fun.fengwk.kkstudio.platform.cloudfs.service.impl;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNode;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudNodeKind;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudTextRevision;
import fun.fengwk.kkstudio.platform.cloudfs.domain.StrictUtf8;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudCycleException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudDirectoryNotEmptyException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditAmbiguousException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudEditPatternNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudFileSystemValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeAlreadyExistsException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeKindConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudNodeNotFoundException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathForbiddenException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudPathValidationException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudRevisionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.domain.error.CloudVersionConflictException;
import fun.fengwk.kkstudio.platform.cloudfs.repository.CloudNodeRepository;
import fun.fengwk.kkstudio.platform.cloudfs.repository.CloudTextRevisionRepository;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudFileSystemService;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link CloudFileSystemService} 核心实现。 */
@Service
public class CloudFileSystemServiceImpl implements CloudFileSystemService {

  private static final CloudNode VIRTUAL_ROOT =
      CloudNode.builder()
          .id(null)
          .parentId(null)
          .name("")
          .kind(CloudNodeKind.DIRECTORY)
          .version(0L)
          .build();

  private final CloudNodeRepository nodeRepository;
  private final CloudTextRevisionRepository revisionRepository;
  private final ObjectProvider<StorageBlobManager> blobManagerProvider;

  public CloudFileSystemServiceImpl(
      CloudNodeRepository nodeRepository,
      CloudTextRevisionRepository revisionRepository,
      ObjectProvider<StorageBlobManager> blobManagerProvider) {
    this.nodeRepository = Objects.requireNonNull(nodeRepository, "nodeRepository");
    this.revisionRepository = Objects.requireNonNull(revisionRepository, "revisionRepository");
    this.blobManagerProvider = Objects.requireNonNull(blobManagerProvider, "blobManagerProvider");
  }

  private StorageBlobManager requireBlobManager() {
    StorageBlobManager manager = blobManagerProvider.getIfAvailable();
    if (manager == null) {
      throw new IllegalStateException(
          "StorageBlobManager is not available (S3 storage is not enabled)");
    }
    return manager;
  }

  @Override
  public Optional<CloudNode> findNode(CloudPath path) {
    Objects.requireNonNull(path, "path");
    if (path.isRoot()) {
      return Optional.of(VIRTUAL_ROOT);
    }
    UUID currentParentId = null;
    CloudNode current = null;
    List<String> segments = path.segments();
    for (int i = 0; i < segments.size(); i++) {
      String seg = segments.get(i);
      Optional<CloudNode> opt = nodeRepository.findByParentIdAndName(currentParentId, seg);
      if (opt.isEmpty()) {
        return Optional.empty();
      }
      current = opt.get();
      if (i < segments.size() - 1 && !current.isDirectory()) {
        return Optional.empty();
      }
      currentParentId = current.getId();
    }
    return Optional.ofNullable(current);
  }

  @Override
  public CloudNode getNode(CloudPath path) {
    return findNode(path).orElseThrow(() -> new CloudNodeNotFoundException(path));
  }

  @Override
  public List<CloudNode> listChildren(CloudPath directoryPath) {
    Objects.requireNonNull(directoryPath, "directoryPath");
    if (directoryPath.isRoot()) {
      return nodeRepository.findByParentId(null).stream()
          .filter(n -> !CloudPath.ARTIFACTS_ROOT_SEGMENT.equals(n.getName()))
          .toList();
    }
    CloudNode dir = getNode(directoryPath);
    if (!dir.isDirectory()) {
      throw new CloudNodeKindConflictException(
          directoryPath, CloudNodeKind.DIRECTORY, dir.getKind());
    }
    return nodeRepository.findByParentId(dir.getId());
  }

  @Override
  @Transactional
  public CloudNode mkdir(CloudPath path, boolean recursive) {
    Objects.requireNonNull(path, "path");
    if (path.isRoot()) {
      throw new CloudPathForbiddenException(path, "Cannot create root directory");
    }
    if (path.isArtifactPath()) {
      throw new CloudPathForbiddenException(path, "Public mutation forbidden under /.artifacts");
    }
    if (!recursive) {
      CloudPath parentPath = path.parent();
      UUID parentId = null;
      if (!parentPath.isRoot()) {
        CloudNode parentNode = resolveExistingDirectoryForUpdate(parentPath);
        parentId = parentNode.getId();
      }
      Optional<CloudNode> existing =
          nodeRepository.findByParentIdAndNameForUpdate(parentId, path.name());
      if (existing.isPresent()) {
        throw new CloudNodeAlreadyExistsException(path);
      }
      CloudNode dir =
          CloudNode.builder()
              .id(UUID.randomUUID())
              .parentId(parentId)
              .name(path.name())
              .kind(CloudNodeKind.DIRECTORY)
              .version(0L)
              .build();
      boolean inserted = nodeRepository.insertIfAbsent(dir);
      if (!inserted) {
        throw new CloudNodeAlreadyExistsException(path);
      }
      return dir;
    }
    return ensureDirectories(path);
  }

  @Override
  @Transactional
  public CloudTextRevision writeText(CloudPath path, String content, long expectedRevision) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(content, "content");
    if (path.isRoot()) {
      throw new CloudPathForbiddenException(path, "Cannot write to root directory");
    }
    if (path.isArtifactPath()) {
      throw new CloudPathForbiddenException(path, "Public mutation forbidden under /.artifacts");
    }
    if (expectedRevision < 0) {
      throw new CloudFileSystemValidationException("expectedRevision must be non-negative");
    }
    byte[] utf8Bytes = StrictUtf8.encode(content, "text content");
    if (utf8Bytes.length > MAX_TEXT_BYTES) {
      throw new CloudFileSystemValidationException(
          String.format(
              "Text content size (%d bytes) exceeds maximum limit of %d bytes (1 MiB)",
              utf8Bytes.length, MAX_TEXT_BYTES));
    }

    if (expectedRevision == 0) {
      UUID parentId = null;
      if (!path.parent().isRoot()) {
        CloudNode parentDir = ensureDirectories(path.parent());
        parentId = parentDir.getId();
      }
      Optional<CloudNode> existing =
          nodeRepository.findByParentIdAndNameForUpdate(parentId, path.name());
      if (existing.isPresent()) {
        CloudNode existingNode = existing.get();
        long curRev = 0L;
        if (existingNode.isText()) {
          Optional<CloudTextRevision> curRevOpt =
              revisionRepository.findCurrentByNodeId(existingNode.getId());
          if (curRevOpt.isPresent()) {
            curRev = curRevOpt.get().getRevision();
          }
        }
        throw new CloudRevisionConflictException(path, curRev, 0L);
      }

      UUID nodeId = UUID.randomUUID();
      CloudNode textNode =
          CloudNode.builder()
              .id(nodeId)
              .parentId(parentId)
              .name(path.name())
              .kind(CloudNodeKind.TEXT)
              .version(0L)
              .build();
      boolean inserted = nodeRepository.insertIfAbsent(textNode);
      if (!inserted) {
        Optional<CloudNode> winner =
            nodeRepository.findByParentIdAndNameForUpdate(parentId, path.name());
        long curRev = 0L;
        if (winner.isPresent()) {
          if (!winner.get().isText()) {
            throw new CloudNodeKindConflictException(
                path, CloudNodeKind.TEXT, winner.get().getKind());
          }
          curRev =
              revisionRepository
                  .findCurrentByNodeId(winner.get().getId())
                  .map(CloudTextRevision::getRevision)
                  .orElse(0L);
        }
        throw new CloudRevisionConflictException(path, curRev, 0L);
      }

      String sha256 = sha256Hex(utf8Bytes);
      CloudTextRevision rev =
          CloudTextRevision.builder()
              .nodeId(nodeId)
              .revision(1L)
              .content(content)
              .sizeBytes(utf8Bytes.length)
              .sha256(sha256)
              .current(true)
              .build();
      revisionRepository.insert(rev);
      return rev;
    }

    CloudNode node = resolveExistingNodeForUpdate(path);
    if (!node.isText()) {
      throw new CloudNodeKindConflictException(path, CloudNodeKind.TEXT, node.getKind());
    }
    Optional<CloudTextRevision> curOpt = revisionRepository.findCurrentByNodeId(node.getId());
    long curRev = curOpt.map(CloudTextRevision::getRevision).orElse(0L);
    if (curRev != expectedRevision) {
      throw new CloudRevisionConflictException(path, curRev, expectedRevision);
    }
    int unset = revisionRepository.unsetCurrent(node.getId(), expectedRevision);
    if (unset == 0) {
      long latestRev =
          revisionRepository
              .findCurrentByNodeId(node.getId())
              .map(CloudTextRevision::getRevision)
              .orElse(curRev);
      throw new CloudRevisionConflictException(path, latestRev, expectedRevision);
    }
    String sha256 = sha256Hex(utf8Bytes);
    CloudTextRevision newRev =
        CloudTextRevision.builder()
            .nodeId(node.getId())
            .revision(expectedRevision + 1)
            .content(content)
            .sizeBytes(utf8Bytes.length)
            .sha256(sha256)
            .current(true)
            .build();
    revisionRepository.insert(newRev);
    int touched = nodeRepository.touch(node.getId(), Instant.now());
    if (touched == 0) {
      throw new IllegalStateException(
          "Failed to touch node after revision insert: " + node.getId());
    }
    return newRev;
  }

  @Override
  @Transactional
  public CloudTextRevision editText(
      CloudPath path,
      String oldString,
      String newString,
      long expectedRevision,
      boolean replaceAll) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(oldString, "oldString");
    Objects.requireNonNull(newString, "newString");
    if (oldString.isEmpty()) {
      throw new CloudFileSystemValidationException("oldString must not be empty");
    }
    if (path.isRoot()) {
      throw new CloudPathForbiddenException(path, "Cannot edit root directory");
    }
    if (path.isArtifactPath()) {
      throw new CloudPathForbiddenException(path, "Public mutation forbidden under /.artifacts");
    }
    if (expectedRevision <= 0) {
      throw new CloudFileSystemValidationException("expectedRevision must be positive for edit");
    }
    StrictUtf8.encode(oldString, "oldString");
    StrictUtf8.encode(newString, "newString");

    CloudNode node = resolveExistingNodeForUpdate(path);
    if (!node.isText()) {
      throw new CloudNodeKindConflictException(path, CloudNodeKind.TEXT, node.getKind());
    }

    CloudTextRevision currentRev =
        revisionRepository
            .findCurrentByNodeId(node.getId())
            .orElseThrow(() -> new CloudRevisionConflictException(path, 0L, expectedRevision));
    if (currentRev.getRevision() != expectedRevision) {
      throw new CloudRevisionConflictException(path, currentRev.getRevision(), expectedRevision);
    }

    String content = currentRev.getContent();
    int count = countOccurrences(content, oldString);
    if (count == 0) {
      throw new CloudEditPatternNotFoundException(path);
    }
    if (count > 1 && !replaceAll) {
      throw new CloudEditAmbiguousException(path, count);
    }

    String newContent;
    if (replaceAll) {
      newContent = content.replace(oldString, newString);
    } else {
      int idx = content.indexOf(oldString);
      newContent =
          content.substring(0, idx) + newString + content.substring(idx + oldString.length());
    }

    byte[] utf8Bytes = StrictUtf8.encode(newContent, "edited text content");
    if (utf8Bytes.length > MAX_TEXT_BYTES) {
      throw new CloudFileSystemValidationException(
          String.format(
              "Text content size (%d bytes) exceeds maximum limit of %d bytes (1 MiB)",
              utf8Bytes.length, MAX_TEXT_BYTES));
    }

    int unset = revisionRepository.unsetCurrent(node.getId(), expectedRevision);
    if (unset == 0) {
      long latestRev =
          revisionRepository
              .findCurrentByNodeId(node.getId())
              .map(CloudTextRevision::getRevision)
              .orElse(currentRev.getRevision());
      throw new CloudRevisionConflictException(path, latestRev, expectedRevision);
    }

    String sha256 = sha256Hex(utf8Bytes);
    CloudTextRevision newRev =
        CloudTextRevision.builder()
            .nodeId(node.getId())
            .revision(expectedRevision + 1)
            .content(newContent)
            .sizeBytes(utf8Bytes.length)
            .sha256(sha256)
            .current(true)
            .build();
    revisionRepository.insert(newRev);
    int touched = nodeRepository.touch(node.getId(), Instant.now());
    if (touched == 0) {
      throw new IllegalStateException(
          "Failed to touch node after revision insert: " + node.getId());
    }
    return newRev;
  }

  @Override
  @Transactional
  public CloudNode moveNode(CloudPath sourcePath, CloudPath targetPath, long expectedVersion) {
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(targetPath, "targetPath");
    if (sourcePath.isRoot() || targetPath.isRoot()) {
      throw new CloudPathForbiddenException(
          sourcePath.isRoot() ? sourcePath : targetPath, "Cannot move root directory");
    }
    if (sourcePath.isArtifactPath() || targetPath.isArtifactPath()) {
      throw new CloudPathForbiddenException(
          sourcePath.isArtifactPath() ? sourcePath : targetPath,
          "Public mutation forbidden under /.artifacts");
    }
    if (sourcePath.equals(targetPath)) {
      throw new CloudPathValidationException(
          "Source path and target path must differ: " + sourcePath);
    }
    if (targetPath.isDescendantOf(sourcePath)) {
      throw new CloudCycleException(sourcePath, targetPath);
    }
    if (expectedVersion < 0) {
      throw new CloudFileSystemValidationException("expectedVersion must be non-negative");
    }

    CloudPath targetParentPath = targetPath.parent();
    CloudNode sourceNode;
    UUID targetParentId = null;

    if (targetParentPath.isRoot()) {
      sourceNode = resolveExistingNodeForUpdate(sourcePath);
    } else {
      int cmp = sourcePath.value().compareTo(targetParentPath.value());
      CloudNode targetParent;
      if (cmp <= 0) {
        sourceNode = resolveExistingNodeForUpdate(sourcePath);
        targetParent = resolveExistingDirectoryForUpdate(targetParentPath);
      } else {
        targetParent = resolveExistingDirectoryForUpdate(targetParentPath);
        sourceNode = resolveExistingNodeForUpdate(sourcePath);
      }
      targetParentId = targetParent.getId();
    }

    if (sourceNode.getVersion() != expectedVersion) {
      throw new CloudVersionConflictException(sourcePath, sourceNode.getVersion(), expectedVersion);
    }

    Optional<CloudNode> existingTarget =
        nodeRepository.findByParentIdAndNameForUpdate(targetParentId, targetPath.name());
    if (existingTarget.isPresent()) {
      throw new CloudNodeAlreadyExistsException(targetPath);
    }

    int updated;
    try {
      updated =
          nodeRepository.updateParentAndName(
              sourceNode.getId(),
              targetParentId,
              targetPath.name(),
              expectedVersion,
              Instant.now());
    } catch (DuplicateKeyException e) {
      throw new CloudNodeAlreadyExistsException(targetPath);
    }
    if (updated == 0) {
      long curVer =
          nodeRepository
              .findById(sourceNode.getId())
              .map(CloudNode::getVersion)
              .orElse(sourceNode.getVersion());
      throw new CloudVersionConflictException(sourcePath, curVer, expectedVersion);
    }
    return nodeRepository.findById(sourceNode.getId()).orElseThrow();
  }

  @Override
  @Transactional
  public void deleteNode(CloudPath path, long expectedVersion) {
    Objects.requireNonNull(path, "path");
    if (path.isRoot()) {
      throw new CloudPathForbiddenException(path, "Cannot delete root directory");
    }
    if (path.isArtifactPath()) {
      throw new CloudPathForbiddenException(path, "Public mutation forbidden under /.artifacts");
    }
    if (expectedVersion < 0) {
      throw new CloudFileSystemValidationException("expectedVersion must be non-negative");
    }

    CloudNode node = resolveExistingNodeForUpdate(path);
    if (node.getVersion() != expectedVersion) {
      throw new CloudVersionConflictException(path, node.getVersion(), expectedVersion);
    }

    if (node.isDirectory()) {
      int childrenCount = nodeRepository.countByParentId(node.getId());
      if (childrenCount > 0) {
        throw new CloudDirectoryNotEmptyException(path, childrenCount);
      }
    } else if (node.isText()) {
      revisionRepository.deleteByNodeId(node.getId());
    }

    int deleted = nodeRepository.deleteByIdAndVersion(node.getId(), expectedVersion);
    if (deleted == 0) {
      long curVer =
          nodeRepository
              .findById(node.getId())
              .map(CloudNode::getVersion)
              .orElse(node.getVersion());
      throw new CloudVersionConflictException(path, curVer, expectedVersion);
    }

    if (node.isBlob()) {
      requireBlobManager().release(node.getBlobId());
    }
  }

  @Override
  @Transactional
  public CloudNode createBlobNode(CloudPath path, UUID blobId) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(blobId, "blobId");
    if (path.isRoot()) {
      throw new CloudPathForbiddenException(path, "Cannot create blob at root directory");
    }
    if (path.isArtifactPath()) {
      throw new CloudPathForbiddenException(path, "Public mutation forbidden under /.artifacts");
    }

    StorageBlob blob = requireBlobManager().getBlob(blobId);
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      throw new StorageResourceNotFoundException("blob", blobId.toString());
    }

    UUID parentId = null;
    if (!path.parent().isRoot()) {
      CloudNode parentDir = ensureDirectories(path.parent());
      parentId = parentDir.getId();
    }
    Optional<CloudNode> existing =
        nodeRepository.findByParentIdAndNameForUpdate(parentId, path.name());
    if (existing.isPresent()) {
      throw new CloudNodeAlreadyExistsException(path);
    }

    CloudNode node =
        CloudNode.builder()
            .id(UUID.randomUUID())
            .parentId(parentId)
            .name(path.name())
            .kind(CloudNodeKind.BLOB)
            .version(0L)
            .blobId(blobId)
            .build();
    boolean inserted = nodeRepository.insertIfAbsent(node);
    if (!inserted) {
      throw new CloudNodeAlreadyExistsException(path);
    }
    requireBlobManager().retain(blobId);
    return node;
  }

  @Override
  public CloudTextRevision readCurrentText(CloudPath path) {
    Objects.requireNonNull(path, "path");
    CloudNode node = getNode(path);
    if (!node.isText()) {
      throw new CloudNodeKindConflictException(path, CloudNodeKind.TEXT, node.getKind());
    }
    return revisionRepository
        .findCurrentByNodeId(node.getId())
        .orElseThrow(
            () -> new CloudNodeNotFoundException("No current text revision found for " + path));
  }

  @Override
  public CloudTextRevision readTextRevision(CloudPath path, long revision) {
    Objects.requireNonNull(path, "path");
    CloudNode node = getNode(path);
    if (!node.isText()) {
      throw new CloudNodeKindConflictException(path, CloudNodeKind.TEXT, node.getKind());
    }
    return revisionRepository
        .findByNodeIdAndRevision(node.getId(), revision)
        .orElseThrow(
            () ->
                new CloudNodeNotFoundException(
                    String.format("Revision %d not found for %s", revision, path)));
  }

  private Optional<CloudNode> resolvePathForUpdate(CloudPath path) {
    Objects.requireNonNull(path, "path");
    if (path.isRoot()) {
      return Optional.of(VIRTUAL_ROOT);
    }
    UUID currentParentId = null;
    CloudNode current = null;
    List<String> segments = path.segments();
    for (int i = 0; i < segments.size(); i++) {
      String seg = segments.get(i);
      Optional<CloudNode> opt = nodeRepository.findByParentIdAndNameForUpdate(currentParentId, seg);
      if (opt.isEmpty()) {
        return Optional.empty();
      }
      current = opt.get();
      boolean isIntermediate = (i < segments.size() - 1);
      if (isIntermediate && !current.isDirectory()) {
        throw new CloudNodeKindConflictException(path, CloudNodeKind.DIRECTORY, current.getKind());
      }
      currentParentId = current.getId();
    }
    return Optional.ofNullable(current);
  }

  private CloudNode resolveExistingNodeForUpdate(CloudPath path) {
    return resolvePathForUpdate(path).orElseThrow(() -> new CloudNodeNotFoundException(path));
  }

  private CloudNode resolveExistingDirectoryForUpdate(CloudPath path) {
    if (path.isRoot()) {
      return VIRTUAL_ROOT;
    }
    CloudNode node = resolveExistingNodeForUpdate(path);
    if (!node.isDirectory()) {
      throw new CloudNodeKindConflictException(path, CloudNodeKind.DIRECTORY, node.getKind());
    }
    return node;
  }

  private CloudNode ensureDirectories(CloudPath path) {
    if (path.isRoot()) {
      return VIRTUAL_ROOT;
    }
    UUID currentParentId = null;
    CloudNode current = null;
    List<String> segments = path.segments();
    for (String seg : segments) {
      Optional<CloudNode> opt = nodeRepository.findByParentIdAndNameForUpdate(currentParentId, seg);
      if (opt.isEmpty()) {
        UUID newId = UUID.randomUUID();
        CloudNode newDir =
            CloudNode.builder()
                .id(newId)
                .parentId(currentParentId)
                .name(seg)
                .kind(CloudNodeKind.DIRECTORY)
                .version(0L)
                .build();
        boolean inserted = nodeRepository.insertIfAbsent(newDir);
        if (inserted) {
          current = newDir;
          currentParentId = newId;
        } else {
          current =
              nodeRepository
                  .findByParentIdAndNameForUpdate(currentParentId, seg)
                  .orElseThrow(
                      () ->
                          new CloudNodeNotFoundException(
                              "Failed to resolve directory segment: " + seg));
          if (!current.isDirectory()) {
            throw new CloudNodeKindConflictException(
                path, CloudNodeKind.DIRECTORY, current.getKind());
          }
          currentParentId = current.getId();
        }
      } else {
        current = opt.get();
        if (!current.isDirectory()) {
          throw new CloudNodeKindConflictException(
              path, CloudNodeKind.DIRECTORY, current.getKind());
        }
        currentParentId = current.getId();
      }
    }
    return current;
  }

  private static int countOccurrences(String text, String pattern) {
    int count = 0;
    int idx = 0;
    while ((idx = text.indexOf(pattern, idx)) != -1) {
      count++;
      idx += pattern.length();
    }
    return count;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(bytes);
      StringBuilder sb = new StringBuilder(64);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
