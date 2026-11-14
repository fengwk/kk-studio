package fun.fengwk.kkstudio.harness.runtime.execution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

/**
 * journal 单文件落盘：写临时文件 → fsync → 原子移动到 canonical 路径 → fsync 目录。
 *
 * <p>任何一步失败都删除临时文件并把清理失败作为 suppressed 附在原始失败上；移动失败绝不留下半写文件。权限只在文件系统支持 posix 时收紧。
 */
final class AtomicJournalFile {

  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final Path directory;

  AtomicJournalFile(Path directory) {
    this.directory = directory;
  }

  /**
   * 首次发布：目标已存在时原子移动失败，由 {@code onAlreadyExists} 决定抛出的冲突。
   *
   * @throws IOException 写入 / 移动 / fsync 失败，临时文件已清理
   */
  void publishNew(Path target, String content, Runnable onAlreadyExists) throws IOException {
    Path temporary = temporaryPath();
    boolean moved = false;
    Throwable failure = null;
    try {
      writeDurably(temporary, content);
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (FileAlreadyExistsException error) {
        onAlreadyExists.run();
      }
      moved = true;
      forceDirectoryForWrite();
    } catch (IOException | RuntimeException error) {
      failure = error;
      throw error;
    } finally {
      if (!moved) {
        deleteTemporary(temporary, failure);
      }
    }
  }

  /** 同身份覆盖：写临时文件后原子替换 canonical 路径。 */
  void replace(Path target, String content) throws IOException {
    Path temporary = temporaryPath();
    boolean moved = false;
    Throwable failure = null;
    try {
      writeDurably(temporary, content);
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      moved = true;
      forceDirectoryForWrite();
    } catch (IOException | RuntimeException error) {
      failure = error;
      throw error;
    } finally {
      if (!moved) {
        deleteTemporary(temporary, failure);
      }
    }
  }

  private Path temporaryPath() {
    return directory.resolve("." + UUID.randomUUID() + ".tmp");
  }

  private static void writeDurably(Path file, String content) throws IOException {
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

  private static void setFilePermissions(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
    }
  }

  private static void deleteTemporary(Path temporary, Throwable failure) {
    try {
      Files.deleteIfExists(temporary);
    } catch (IOException error) {
      if (failure != null) {
        failure.addSuppressed(error);
      }
    }
  }
}
