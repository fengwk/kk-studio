package fun.fengwk.kkstudio.canvas.function;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Adapter 必须关闭的冻结 Resource original 流。 */
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
