package fun.fengwk.kkstudio.canvas.function;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Adapter 必须关闭的冻结 Resource original 流。
 *
 * <p>关闭权完全归 adapter：未读到 EOF 就关闭时，Runtime 的平台读取流会中止连接而不是排空剩余响应体，因此提前放弃是安全的；读到 EOF 后关闭则正常释放连接。
 */
public final class CanvasFunctionResourceStream implements AutoCloseable {

  private final InputStream content;
  private final long size;
  private final AutoCloseable closeable;

  public CanvasFunctionResourceStream(InputStream content, long size, AutoCloseable closeable) {
    this.content = Objects.requireNonNull(content, "content");
    if (size < 0L) {
      throw new IllegalArgumentException("size must be >= 0");
    }
    this.size = size;
    this.closeable = Objects.requireNonNull(closeable, "closeable");
  }

  public InputStream content() {
    return content;
  }

  public long size() {
    return size;
  }

  @Override
  public void close() throws IOException {
    try {
      closeable.close();
    } catch (IOException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IOException("failed to close Canvas Function resource stream", exception);
    }
  }
}
