package fun.fengwk.kkstudio.core.ai.image;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.core.CoreTestApplication;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Response;
import fun.fengwk.kkstudio.core.ai.image.model.GptImage2Size;
import fun.fengwk.kkstudio.core.ai.image.model.ImageData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link GptImage2Service} 单元测试.
 *
 * @author fengwk
 */
@SpringBootTest(classes = CoreTestApplication.class)
public class GptImage2ServiceTest {

  @Autowired private GptImage2Service gptImage2Service;

  @Test
  @EnabledIfSystemProperty(named = "kk.studio.test.gpt-image-2.enabled", matches = "true")
  public void testGenerateTextToImage() throws IOException, InterruptedException {
    GptImage2Response resp = gptImage2Service.generate("一只金色的小猫", GptImage2Size.AUTO, null);
    assertNotNull(resp);
    assertNotNull(resp.getImage());
    assertNotNull(resp.getImage().getBase64());

    Path outputFile = resp.getImage().writeToTempFile("gpt-image-2-");
    assertTrue(Files.exists(outputFile));
    System.out.println("Image written to: " + outputFile);
  }

  @Test
  @EnabledIfSystemProperty(named = "kk.studio.test.gpt-image-2.enabled", matches = "true")
  public void testGenerateImageToImage() throws IOException, InterruptedException {
    ImageData imageData =
        ImageData.fromFile(Path.of("/home/fengwk/Documents/IP/江湖多周目/assets/陈镇岳_三视图.png"));
    GptImage2Response resp =
        gptImage2Service.generate("这个人在跳舞", GptImage2Size.AUTO, List.of(imageData));
    assertNotNull(resp);
    assertNotNull(resp.getImage());
    assertNotNull(resp.getImage().getBase64());

    Path outputFile = resp.getImage().writeToTempFile("gpt-image-2-");
    assertTrue(Files.exists(outputFile));
    System.out.println("Image written to: " + outputFile);
  }
}
