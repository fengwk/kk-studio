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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 单机文件实现：每个执行身份一个 {@code <executionId>.json}，目录与文件分别限定为 0700/0600。
 *
 * <p>进程启动时以独占文件锁占用目录，防止两个 Studio 进程共用同一目录。写入先在同目录创建临时文件、设置权限并 force，再原子 rename； 读取遇到损坏或文件名/内容身份不一致时
 * fail-closed，不删除原文件。
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
  private final Object writeLock = new Object();
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
    synchronized (writeLock) {
      ensureOpen();
      Path target = fileFor(record.executionId());
      ExecutionExitJournalRecord existing = readIfPresent(target);
      if (existing != null) {
        if (existing.sameIdentity(record) && existing.targetState() == record.targetState()) {
          forceDirectoryForWrite();
          return existing;
        }
        throw new ExecutionExitJournalException(
            "execution exit journal identity or target conflict for " + record.executionId());
      }
      Path temporary = directory.resolve("." + UUID.randomUUID() + ".tmp");
      boolean moved = false;
      Throwable failure = null;
      try {
        writeDurably(temporary, encode(record));
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException error) {
          throw new ExecutionExitJournalException(
              "execution exit journal identity conflict for " + record.executionId(), error);
        } catch (AtomicMoveNotSupportedException error) {
          throw new ExecutionExitJournalException(
              "execution exit journal does not support atomic rename", error);
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
    }
  }

  @Override
  public void delete(UUID executionId) {
    Objects.requireNonNull(executionId, "executionId");
    synchronized (writeLock) {
      ensureOpen();
      try {
        Files.deleteIfExists(fileFor(executionId));
        forceDirectory();
      } catch (IOException error) {
        throw new ExecutionExitJournalException(
            "cannot delete execution exit journal entry for " + executionId, error);
      }
    }
  }

  @Override
  public List<ExecutionExitJournalRecord> loadAll() {
    synchronized (writeLock) {
      ensureOpen();
      List<Path> files = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
        for (Path file : stream) {
          if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ExecutionExitJournalException(
                "execution exit journal entry is not a regular file: " + file.getFileName());
          }
          files.add(file);
        }
      } catch (IOException error) {
        throw new ExecutionExitJournalException(
            "cannot list execution exit journal directory: " + directory, error);
      }
      files.sort(Comparator.comparing(path -> path.getFileName().toString()));
      List<ExecutionExitJournalRecord> records = new ArrayList<>(files.size());
      for (Path file : files) {
        ExecutionExitJournalRecord record = read(file);
        String expectedName = record.executionId() + SUFFIX;
        if (!file.getFileName().toString().equals(expectedName)) {
          throw new ExecutionExitJournalException(
              "execution exit journal file identity mismatch: " + file.getFileName());
        }
        records.add(record);
      }
      return List.copyOf(records);
    }
  }

  @Override
  public void close() {
    synchronized (writeLock) {
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
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new ExecutionExitJournalException(
          "execution exit journal entry is not a regular file: " + file.getFileName());
    }
    String json;
    try {
      json = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new ExecutionExitJournalException(
          "cannot read execution exit journal file: " + file.getFileName(), error);
    }
    try {
      JsonNode parsed = MAPPER.readTree(json);
      if (!(parsed instanceof ObjectNode node)) {
        throw new IllegalArgumentException("journal entry must be a JSON object");
      }
      return decode(node);
    } catch (JacksonException | IllegalArgumentException error) {
      throw new ExecutionExitJournalException(
          "corrupt execution exit journal file: " + file.getFileName(), error);
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

  private static void deleteTemporary(Path path, Throwable primary) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException cleanup) {
      if (primary != null) {
        primary.addSuppressed(cleanup);
      }
    }
  }

  private static void closeAfterConstructorFailure(
      FileLock acquired, FileChannel channel, Throwable primary) {
    if (acquired != null && acquired.isValid()) {
      try {
        acquired.release();
      } catch (IOException cleanup) {
        primary.addSuppressed(cleanup);
      }
    }
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException cleanup) {
        primary.addSuppressed(cleanup);
      }
    }
  }
}
