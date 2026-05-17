package fun.fengwk.kkstudio.core.ai.image.model;

import fun.fengwk.convention4j.common.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GptImage2Request} 单元测试.
 *
 * @author fengwk
 */
public class GptImage2RequestTest {

    @Test
    public void testSerializeAutoSize() {
        GptImage2Request request = new GptImage2Request();
        String json = JsonUtils.toJson(request);
        assertTrue(json.contains("\"size\":\"\""));
    }

    @Test
    public void testSerializeWidescreenSize() {
        GptImage2Request request = new GptImage2Request();
        request.setSize(GptImage2Size.WIDESCREEN_16_9);
        String json = JsonUtils.toJson(request);
        assertTrue(json.contains("\"size\":\"16:9\""));
    }

}
