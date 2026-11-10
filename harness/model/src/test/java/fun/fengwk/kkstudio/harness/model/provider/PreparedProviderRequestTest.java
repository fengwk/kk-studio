package fun.fengwk.kkstudio.harness.model.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.limits.AudioRequestLimits;
import fun.fengwk.kkstudio.harness.model.limits.DocumentRequestLimits;
import fun.fengwk.kkstudio.harness.model.limits.ImageRequestLimits;
import fun.fengwk.kkstudio.harness.model.limits.RequestLimits;
import fun.fengwk.kkstudio.harness.model.limits.RequestLimitsPresets;
import fun.fengwk.kkstudio.harness.model.limits.VideoRequestLimits;
import fun.fengwk.kkstudio.harness.model.provider.PreparedProviderRequest.MediaFact;
import fun.fengwk.kkstudio.harness.model.provider.PreparedProviderRequest.MediaKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 最终 body 计量与发送前限制检查：只检查已知限制，口径分别是 BODY、RAW 与 BASE64 字节。 */
class PreparedProviderRequestTest {

  /** 4 字节原图 → Base64 "AQIDBA==" 共 8 字节。 */
  private static final String IMAGE = "data:image/png;base64,AQIDBA==";

  /** 3 字节 PDF → Base64 "AQID" 共 4 字节。 */
  private static final String PDF = "data:application/pdf;base64,AQID";

  @Test
  void measuresBodyHashAndInlineMediaIncludingToolResults() throws Exception {
    // 意图：bodyHash/字节数来自同一份 body；工具结果中的媒体同样计入（RAW 与 BASE64 分开计量）。
    byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    ProviderRequest request =
        request(
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(new ProviderTextBlock("hi"), new ProviderImageBlock("image/png", IMAGE))),
            new ProviderMessage(
                ProviderMessageRole.TOOL,
                List.of(
                    new ProviderToolResultBlock(
                        "c1",
                        "read",
                        List.of(new ProviderDocumentBlock("application/pdf", PDF)),
                        false,
                        "{}"))));

    PreparedProviderRequest prepared =
        PreparedProviderRequest.prepare(request, body, RequestLimitsPresets.unknown());

    assertArrayEquals(body, prepared.body());
    assertEquals(body.length, prepared.bodyBytes());
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),
        prepared.bodyHash());
    assertEquals(
        List.of(new MediaFact(MediaKind.IMAGE, 4, 8), new MediaFact(MediaKind.DOCUMENT, 3, 4)),
        prepared.media());
    // 未知限制不做任何本地拒绝。
    assertTrue(prepared.limitViolation().isEmpty());
  }

  @Test
  void bodyOverTheKnownLimitIsARequestBodyLimit() {
    PreparedProviderRequest prepared =
        PreparedProviderRequest.prepare(
            request(), new byte[11], limits(10L, ImageRequestLimits.UNKNOWN, null));

    assertEquals(ProviderErrorKind.REQUEST_BODY_LIMIT, prepared.limitViolation().get().kind());
    assertTrue(
        PreparedProviderRequest.prepare(
                request(), new byte[10], limits(10L, ImageRequestLimits.UNKNOWN, null))
            .limitViolation()
            .isEmpty(),
        "a body exactly at the limit is allowed");
  }

  @Test
  void imageCountAndSizeLimitsAreMediaLimits() {
    ProviderRequest twoImages =
        request(
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(
                    new ProviderImageBlock("image/png", IMAGE),
                    new ProviderImageBlock("image/png", IMAGE))));

    assertMediaLimit(twoImages, new ImageRequestLimits(null, null, null, 1, null, null, null));
    // 单张 RAW 4 字节、BASE64 8 字节：两种口径分别检查。
    assertMediaLimit(twoImages, new ImageRequestLimits(3L, null, null, null, null, null, null));
    assertMediaLimit(twoImages, new ImageRequestLimits(null, 7L, null, null, null, null, null));
    // 合计按 BASE64 字节：2 × 8 = 16。
    assertMediaLimit(twoImages, new ImageRequestLimits(null, null, 15L, null, null, null, null));
    assertTrue(
        PreparedProviderRequest.prepare(
                twoImages,
                new byte[1],
                limits(null, new ImageRequestLimits(4L, 8L, 16L, 2, null, null, null), null))
            .limitViolation()
            .isEmpty());
  }

  @Test
  void documentRawLimitsAreMediaLimits() {
    ProviderRequest twoDocuments =
        request(
            new ProviderMessage(
                ProviderMessageRole.USER,
                List.of(
                    new ProviderDocumentBlock("application/pdf", PDF),
                    new ProviderDocumentBlock("application/pdf", PDF))));

    PreparedProviderRequest single =
        PreparedProviderRequest.prepare(
            twoDocuments,
            new byte[1],
            limits(null, ImageRequestLimits.UNKNOWN, new DocumentRequestLimits(2L, null, null)));
    assertEquals(ProviderErrorKind.MEDIA_LIMIT, single.limitViolation().get().kind());
    PreparedProviderRequest total =
        PreparedProviderRequest.prepare(
            twoDocuments,
            new byte[1],
            limits(null, ImageRequestLimits.UNKNOWN, new DocumentRequestLimits(null, 5L, null)));
    assertEquals(ProviderErrorKind.MEDIA_LIMIT, total.limitViolation().get().kind());
  }

  private static void assertMediaLimit(ProviderRequest request, ImageRequestLimits image) {
    PreparedProviderRequest prepared =
        PreparedProviderRequest.prepare(request, new byte[1], limits(null, image, null));
    assertEquals(ProviderErrorKind.MEDIA_LIMIT, prepared.limitViolation().get().kind());
  }

  private static RequestLimits limits(
      Long bodyMaxBytes, ImageRequestLimits image, DocumentRequestLimits document) {
    return new RequestLimits(
        bodyMaxBytes,
        image,
        document,
        AudioRequestLimits.UNKNOWN,
        VideoRequestLimits.UNKNOWN,
        null,
        null,
        null);
  }

  private static ProviderRequest request(ProviderMessage... messages) {
    List<ProviderMessage> all =
        messages.length == 0
            ? List.of(
                new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi"))))
            : List.of(messages);
    return new ProviderRequest(
        new ModelDescriptor("p", "m", "m", Set.of(ModelInputModality.TEXT), true, false),
        new ModelVariant("default", null, PromptCacheRetention.NONE, null),
        1024,
        "system",
        all,
        List.of(),
        ProviderCacheControl.none());
  }
}
