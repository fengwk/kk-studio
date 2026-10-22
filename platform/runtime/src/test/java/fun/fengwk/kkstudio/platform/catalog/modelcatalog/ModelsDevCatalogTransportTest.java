package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

/** 固定端点与响应上限的本地边界；真实主机访问不在单元测试中发起。 */
class ModelsDevCatalogTransportTest {

  @Test
  void pinsHttpsEndpointsTimeoutAndResponseLimit() {
    assertEquals(
        URI.create("https://models.dev/api.json"),
        ModelsDevCatalogTransport.endpoints().get(ModelCatalogSource.MODELS_DEV_V1));
    assertEquals(
        URI.create("https://models.dev/experimental/v2.0/api.json"),
        ModelsDevCatalogTransport.endpoints().get(ModelCatalogSource.MODELS_DEV_V2));
    assertEquals(Duration.ofSeconds(20), ModelsDevCatalogTransport.TIMEOUT);
    assertEquals(32 * 1024 * 1024, ModelsDevCatalogTransport.MAX_BYTES);
  }

  @Test
  void rejectsResponseAboveThirtyTwoMiB() {
    InputStream oversize =
        new InputStream() {
          private long remaining = ModelsDevCatalogTransport.MAX_BYTES + 1L;

          @Override
          public int read() {
            throw new UnsupportedOperationException();
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
              return -1;
            }
            int count = (int) Math.min(length, remaining);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            remaining -= count;
            return count;
          }
        };

    assertThrows(
        ModelCatalogFetchException.class, () -> ModelsDevCatalogTransport.readBounded(oversize));
  }

  @Test
  void acceptsResponseAtThirtyTwoMiB() throws IOException {
    InputStream exact =
        new InputStream() {
          private long remaining = ModelsDevCatalogTransport.MAX_BYTES;

          @Override
          public int read() {
            throw new UnsupportedOperationException();
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            if (remaining <= 0) {
              return -1;
            }
            int count = (int) Math.min(length, remaining);
            Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            remaining -= count;
            return count;
          }
        };

    assertEquals(
        ModelsDevCatalogTransport.MAX_BYTES, ModelsDevCatalogTransport.readBounded(exact).length());
  }
}
