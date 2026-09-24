package fun.fengwk.kkstudio.platform.storage;

import software.amazon.awssdk.http.Abortable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 必须关闭的 S3 对象读取流。
 *
 * <p>正常读完（EOF）后调用 {@link #close()}；提前退出（超时、取消、消费失败）必须调用 {@link #abort()}。
 */
public final class S3ObjectStream implements AutoCloseable {

  private final InputStream inputStream;
  private final S3ObjectMetadata metadata;

  public S3ObjectStream(InputStream inputStream, S3ObjectMetadata metadata) {
    this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
    this.metadata = Objects.requireNonNull(metadata, "metadata");
  }

  public InputStream inputStream() {
    return inputStream;
  }

  public S3ObjectMetadata metadata() {
    return metadata;
  }

  @Override
  public void close() throws IOException {
    inputStream.close();
  }

  /**
   * 中止读取并丢弃未消费的响应体。
   *
   * <p>S3 同步响应流（Apache 客户端）在 {@code close()} 时会读完剩余响应体以便复用连接，未读完时可能无界阻塞；因此提前退出必须先 abort
   * 关停底层连接，再关闭本地句柄。关闭阶段的失败不抛出（调用方已在处理真正的读取失败）；abort 本身的失败会向上抛出，因为它意味着连接没有被关停。
   */
  public void abort() {
    try {
      if (inputStream instanceof Abortable abortable) {
        abortable.abort();
      }
    } finally {
      try {
        inputStream.close();
      } catch (IOException ignored) {
        // 已中止的连接在关闭时无法再排空响应体，这里的失败不影响真正的读取结论。
      }
    }
  }
}
