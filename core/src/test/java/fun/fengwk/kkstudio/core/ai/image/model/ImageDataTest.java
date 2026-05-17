package fun.fengwk.kkstudio.core.ai.image.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

}
