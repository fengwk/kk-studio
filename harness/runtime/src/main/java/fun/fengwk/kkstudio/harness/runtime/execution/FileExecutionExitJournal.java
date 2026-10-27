package fun.fengwk.kkstudio.harness.runtime.execution;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单机文件实现：每个执行身份一个 {@code <executionId>.json}，目录与文件分别限定为 0700/0600。
 *
 * <p>进程启动时以独占文件锁占用目录，防止两个 Studio 进程共用同一目录。写入先在同目录创建临时文件、设置权限并 force，再原子 rename； 读取遇到无法解码、
 * 身份不符或类型异常的文件时只记录该文件失败，不删除或覆盖原文件，健康记录仍返回。
 */
public final class FileExecutionExitJournal implements ExecutionExitJournal {

  private static final String LOCK_FILE = ".lock";
  private static final String SUFFIX = ".json";
  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final Path directory;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final ReentrantLock writeLock = new ReentrantLock();
  private boolean closed;

  public FileExecutionExitJournal(Path directory) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
    createDirectory();
    FileChannel channel = null;
    FileLock acquired = null;
    try {
      Path lockPath = this.directory.resolve(LOCK_FILE);
      if (Files.isSymbolicLink(lockPath)
          || (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
              && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
        throw new ExecutionExitJournalException(
            "execution exit journal lock is not a regular file: " + lockPath);
      }
      channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      setFilePermissions(lockPath);
      try {
        acquired = channel.tryLock();
      } catch (OverlappingFileLockException error) {
        throw new ExecutionExitJournalException(
            "execution exit journal directory is already locked: " + this.directory, error);
      }
      if (acquired == null) {
        throw new ExecutionExitJournalException(
            "execution exit journal directory is already locked: " + this.directory);
      }
    } catch (IOException | RuntimeException error) {
      closeAfterConstructorFailure(acquired, channel, error);
      if (error instanceof ExecutionExitJournalException journalError) {
        throw journalError;
      }
      throw new ExecutionExitJournalException(
          "cannot lock execution exit journal directory: " + this.directory, error);
    }
    this.lockChannel = channel;
    this.lock = acquired;
  }

  @Override
  public ExecutionExitJournalRecord write(ExecutionExitJournalRecord record) {
    Objects.requireNonNull(record, "record");
    writeLock.lock();
    try {
      ensureOpen();
      Path target = fileFor(record.executionId());
      ExecutionExitJournalRecord existing = readIfPresent(target);
      if (existing != null) {
        if (!existing.sameIdentity(record)) {
          throw new ExecutionExitJournalConflictException(
              ExecutionExitJournalConflictException.Conflict.IDENTITY,
              "execution exit journal identity conflict for " + record.executionId());
        }
        if (existing.targetState() != record.targetState()) {
          throw new ExecutionExitJournalConflictException(
              ExecutionExitJournalConflictException.Conflict.TARGET,
              "execution exit journal target conflict for " + record.executionId());
        }
        forceDirectoryForWrite();
        return existing;
      }
      Path temporary = directory.resolve("." + UUID.randomUUID() + ".tmp");
      boolean moved = false;
      Throwable failure = null;
      try {
        writeDurably(temporary, encode(record));
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException error) {
          throw new ExecutionExitJournalConflictException(
              ExecutionExitJournalConflictException.Conflict.IDENTITY,
              "execution exit journal identity conflict for " + record.executionId(),
              error);
        } catch (IOException error) {
          throw new ExecutionExitJournalException(
              "cannot persist execution exit journal entry for " + record.executionId(), error);
        }
        moved = true;
        forceDirectoryForWrite();
        return record;
      } catch (IOException | RuntimeException error) {
        failure = error;
        if (error instanceof ExecutionExitJournalException journalError) {
          throw journalError;
        }
        throw new ExecutionExitJournalException(
            "cannot persist execution exit journal entry for " + record.executionId(), error);
      } finally {
        if (!moved) {
          deleteTemporary(temporary, failure);
        }
      }
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void delete(UUID executionId) {
    Objects.requireNonNull(executionId, "executionId");
    writeLock.lock();
    try {
      ensureOpen();
      try {
        Files.deleteIfExists(fileFor(executionId));
        forceDirectory();
      } catch (IOException error) {
        throw new ExecutionExitJournalException(
            "cannot delete execution exit journal entry for " + executionId, error);
      }
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public ExecutionExitJournalSnapshot loadAll() {
    writeLock.lock();
    try {
      ensureOpen();
      List<Path> files = new ArrayList<>();
      List<ExecutionExitJournalFailure> failures = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
        for (Path file : stream) {
          if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            failures.add(
                new ExecutionExitJournalFailure(
                    file.getFileName().toString(),
                    ExecutionExitJournalFailure.Reason.INVALID_FILE_TYPE,
                    null));
            continue;
          }
          files.add(file);
        }
      } catch (IOException | DirectoryIteratorException error) {
        throw new ExecutionExitJournalException(
            "cannot list execution exit journal directory: " + directory, error);
      }
      files.sort(Comparator.comparing(path -> path.getFileName().toString()));
      List<ExecutionExitJournalRecord> records = new ArrayList<>(files.size());
      for (Path file : files) {
        String fileName = file.getFileName().toString();
        try {
          ExecutionExitJournalRecord record = readEntry(file);
          if (!fileName.equals(record.executionId() + SUFFIX)) {
            throw new EntryReadFailure(ExecutionExitJournalFailure.Reason.IDENTITY_MISMATCH, null);
          }
          records.add(record);
        } catch (EntryReadFailure failure) {
          failures.add(
              new ExecutionExitJournalFailure(fileName, failure.reason, failure.getCause()));
        }
      }
      return new ExecutionExitJournalSnapshot(records, failures);
    } finally {
      writeLock.unlock();
    }
  }

  @Override
  public void close() {
    writeLock.lock();
    try {
      if (closed) {
        return;
      }
      Throwable failure = null;
      if (lock != null && lock.isValid()) {
        try {
          lock.release();
        } catch (IOException error) {
          failure = error;
        }
      }
      try {
        lockChannel.close();
      } catch (IOException error) {
        if (failure == null) {
          failure = error;
        } else {
          failure.addSuppressed(error);
        }
      }
      closed = true;
      if (failure != null) {
        throw new ExecutionExitJournalException(
            "cannot close execution exit journal directory: " + directory, failure);
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void createDirectory() {
    try {
      if (Files.isSymbolicLink(directory)) {
        throw new ExecutionExitJournalException(
            "execution exit journal path must not be a symbolic link: " + directory);
      }
      if (Files.exists(directory)) {
        if (!Files.isDirectory(directory)) {
          throw new ExecutionExitJournalException(
              "execution exit journal path is not a directory: " + directory);
        }
      } else {
        Files.createDirectories(directory);
      }
      setDirectoryPermissions(directory);
    } catch (IOException error) {
      throw new ExecutionExitJournalException(
          "cannot create execution exit journal directory: " + directory, error);
    }
  }

  private Path fileFor(UUID executionId) {
    return directory.resolve(executionId + SUFFIX);
  }

  private ExecutionExitJournalRecord readIfPresent(Path file) {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    return read(file);
  }

  private ExecutionExitJournalRecord read(Path file) {
    try {
      return readEntry(file);
    } catch (EntryReadFailure failure) {
      String fileName = file.getFileName().toString();
      String message =
          switch (failure.reason) {
            case INVALID_FILE_TYPE -> "execution exit journal entry is not a regular file: ";
            case UNREADABLE -> "cannot read execution exit journal file: ";
            case CORRUPT -> "corrupt execution exit journal file: ";
            case IDENTITY_MISMATCH -> "execution exit journal file identity mismatch: ";
          };
      throw new ExecutionExitJournalException(message + fileName, failure.getCause());
    }
  }

  private ExecutionExitJournalRecord readEntry(Path file) {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new EntryReadFailure(ExecutionExitJournalFailure.Reason.INVALID_FILE_TYPE, null);
    }
    String json;
    try {
      json = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new EntryReadFailure(ExecutionExitJournalFailure.Reason.UNREADABLE, error);
    }
    try {
      JsonNode parsed = MAPPER.readTree(json);
      if (!(parsed instanceof ObjectNode node)) {
        throw new IllegalArgumentException("journal entry must be a JSON object");
      }
      return decode(node);
    } catch (JacksonException | IllegalArgumentException | DateTimeException error) {
      throw new EntryReadFailure(ExecutionExitJournalFailure.Reason.CORRUPT, error);
    }
  }

  private static String encode(ExecutionExitJournalRecord record) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("executionId", record.executionId().toString());
    node.put("threadId", record.threadId().toString());
    node.put("invocationId", record.invocationId().toString());
    node.put("attempt", record.attempt());
    node.put("ownerLeaseToken", record.ownerLeaseToken());
    node.put("kind", record.kind().name());
    if (record.parentExecutionId() == null) {
      node.putNull("parentExecutionId");
    } else {
      node.put("parentExecutionId", record.parentExecutionId().toString());
    }
    node.put("targetState", record.targetState().name());
    node.put("confirmedAt", record.confirmedAt().toString());
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new ExecutionExitJournalException("cannot encode execution exit journal entry", error);
    }
  }

  private static ExecutionExitJournalRecord decode(ObjectNode node) {
    requireFields(
        node,
        "executionId",
        "threadId",
        "invocationId",
        "attempt",
        "ownerLeaseToken",
        "kind",
        "parentExecutionId",
        "targetState",
        "confirmedAt");
    JsonNode attemptNode = node.get("attempt");
    if (attemptNode == null || !attemptNode.isIntegralNumber() || !attemptNode.canConvertToInt()) {
      throw new IllegalArgumentException("attempt must be an integer");
    }
    JsonNode parentNode = node.get("parentExecutionId");
    UUID parent =
        parentNode == null || parentNode.isNull() ? null : uuid(parentNode, "parentExecutionId");
    return new ExecutionExitJournalRecord(
        uuid(node.get("executionId"), "executionId"),
        uuid(node.get("threadId"), "threadId"),
        uuid(node.get("invocationId"), "invocationId"),
        attemptNode.intValue(),
        text(node.get("ownerLeaseToken"), "ownerLeaseToken"),
        ExecutionExitKind.valueOf(text(node.get("kind"), "kind")),
        parent,
        ExecutionExitState.valueOf(text(node.get("targetState"), "targetState")),
        Instant.parse(text(node.get("confirmedAt"), "confirmedAt")));
  }

  private static UUID uuid(JsonNode node, String name) {
    return UUID.fromString(text(node, name));
  }

  private static String text(JsonNode node, String name) {
    if (node == null || !node.isString() || node.stringValue().isEmpty()) {
      throw new IllegalArgumentException(name + " must be non-empty text");
    }
    return node.stringValue();
  }

  private static void requireFields(ObjectNode node, String... names) {
    if (node.size() != names.length) {
      throw new IllegalArgumentException("unexpected execution exit journal fields");
    }
    for (String name : names) {
      if (!node.has(name)) {
        throw new IllegalArgumentException("missing execution exit journal field: " + name);
      }
    }
  }

  private void writeDurably(Path file, String content) throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      setFilePermissions(file);
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  private void forceDirectoryForWrite() {
    try {
      forceDirectory();
    } catch (IOException error) {
      throw new ExecutionExitJournalException(
          "cannot force execution exit journal directory", error);
    }
  }

  private void forceDirectory() throws IOException {
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }

  private static void setDirectoryPermissions(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
    }
  }

  private static void setFilePermissions(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new ExecutionExitJournalException("execution exit journal is closed");
    }
  }

  private void deleteTemporary(Path temporary, Throwable failure) {
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException error) {
      if (failure != null) {
        failure.addSuppressed(error);
      }
    }
  }

  private void closeAfterConstructorFailure(
      FileLock acquired, FileChannel channel, Throwable failure) {
    if (acquired != null && acquired.isValid()) {
      try {
        acquired.release();
      } catch (IOException error) {
        failure.addSuppressed(error);
      }
    }
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException error) {
        failure.addSuppressed(error);
      }
    }
  }

  private static final class EntryReadFailure extends RuntimeException {

    private final ExecutionExitJournalFailure.Reason reason;

    private EntryReadFailure(ExecutionExitJournalFailure.Reason reason, Throwable cause) {
      super(null, cause);
      this.reason = reason;
    }
  }
}
