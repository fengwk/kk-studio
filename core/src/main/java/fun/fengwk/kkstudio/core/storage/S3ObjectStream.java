package fun.fengwk.kkstudio.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 必须关闭的 S3 对象读取流。 */
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
}
