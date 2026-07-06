package fun.fengwk.kkstudio.core.ai.image.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link ImageData} 单元测试.
 *
 * @author fengwk
 */
public class ImageDataTest {

  @Test
  public void testFromFileAndWriteToFile() throws IOException {
    byte[] expected = new byte[] {1, 2, 3, 4};
    Path source = Files.createTempFile("image-data-source-", ".png");
    Files.write(source, expected);

    ImageData imageData = ImageData.fromFile(source);
    assertEquals("image/png", imageData.getMimeType());
    assertArrayEquals(expected, imageData.decode());

    Path target = imageData.writeToTempFile("image-data-target-");
    assertTrue(Files.exists(target));
    assertTrue(target.getFileName().toString().endsWith(".png"));
    assertArrayEquals(expected, Files.readAllBytes(target));
  }

  @Test
  public void testDefaultMimeTypeAndExtension() {
    ImageData imageData = ImageData.of(" ", new byte[] {7, 8});

    assertEquals("application/octet-stream", imageData.getMimeType());
    assertEquals(".bin", imageData.resolveExtension());
    assertArrayEquals(new byte[] {7, 8}, imageData.decode());
  }

  @Test
  public void testRejectInvalidImageData() {
    ImageData imageData = new ImageData();

    assertThrows(NullPointerException.class, () -> ImageData.fromFile((Path) null));
    assertThrows(NullPointerException.class, () -> ImageData.of("image/png", null));
    assertThrows(NullPointerException.class, imageData::decode);
  }
}
