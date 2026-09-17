package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Daemon wire PROGRESS / COMPLETED payload codec 的双向与拒绝契约测试。
 *
 * <p>核心不变量：wire 上永远不出现 resource 字节（既非 Base64 也非二进制帧）；终态 resource 只承载 {@code uploadId} 与
 * 元数据，解码侧把它还原为进程内瞬态 {@code blob-upload:<uploadId>} 引用。
 */
class DaemonCapabilityResultCodecTest {

  private static final String SHA_HELLO =
      "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

  private final DaemonCapabilityResultCodec codec = new DaemonCapabilityResultCodec();

  /** text / json 内容必须保持现有 wire shape，并且文本严格校验为 string。 */
  @Test
  void preservesTextAndJsonWireShape() {
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "call-1",
            List.of(
                new TextResultContent("hello"),
                new JsonResultContent("{\"a\":1}"),
                new JsonResultContent("null")),
            false,
            "{}");

    String payload = codec.encodeCompleted(result, 1024, NoopUploader.INSTANCE);

    assertTrue(payload.contains("\"callId\":\"call-1\""));
    assertTrue(payload.contains("\"error\":false"));
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"text\":\"hello\""));
    assertTrue(payload.contains("\"type\":\"json\""));
    assertTrue(payload.contains("\"json\":{\"a\":1}"));

    EnvironmentCapabilityResult decoded = codec.decodeResult(payload);
    assertEquals(3, decoded.contents().size());
    assertEquals("hello", ((TextResultContent) decoded.contents().get(0)).text());
    assertEquals("{\"a\":1}", ((JsonResultContent) decoded.contents().get(1)).json());
    assertEquals("null", ((JsonResultContent) decoded.contents().get(2)).json());
  }

  /** 已上传的 resource 引用编码为 uploadId 与元数据，wire 上绝不出现任何字节表示。 */
  @Test
  void uploadedResourceEncodesUploadIdAndMetadataOnly() {
    byte[] data = "kk-studio".getBytes(StandardCharsets.UTF_8);
    UUID uploadId = UUID.randomUUID();
    ResourceRef uploaded = uploadedRef(uploadId, "text/plain", "kk.txt", data);
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "call-2", List.of(new ResourceResultContent(uploaded)), false, "{}");

    String payload = codec.encodeCompleted(result, 1024, NoopUploader.INSTANCE);

    assertTrue(payload.contains("\"type\":\"resource\""));
    assertTrue(payload.contains("\"uploadId\":\"" + uploadId + "\""));
    assertTrue(payload.contains("\"mediaType\":\"text/plain\""));
    assertTrue(payload.contains("\"name\":\"kk.txt\""));
    assertTrue(payload.contains("\"size\":" + data.length));
    assertTrue(payload.contains("\"sha256\":\"" + sha256Hex(data) + "\""));
    // 精确形状：resource 只承载 uploadId 与权威元数据，绝无本地上传地址、uri 或任何字节表示。
    assertEquals(
        Set.of("type", "uploadId", "mediaType", "name", "size", "sha256"),
        resourceWireFields(payload));
    assertFalse(payload.contains("blob-upload:"));
    assertFalse(payload.contains("uri"));

    EnvironmentCapabilityResult decoded =
        codec.decodeCompletedForInvocation(payload, "call-2", 1024);
    assertEquals(1, decoded.contents().size());
    ResourceResultContent resource = (ResourceResultContent) decoded.contents().get(0);
    assertEquals(uploadId, resource.resource().blobUploadId());
    assertEquals(ResourceRef.blobUploadUri(uploadId), resource.resource().uri());
    assertEquals("text/plain", resource.resource().mediaType());
    assertEquals("kk.txt", resource.resource().name());
    assertEquals((Long) (long) data.length, resource.resource().size());
    assertEquals(sha256Hex(data), resource.resource().sha256());
    assertNull(resource.textMetadata());
  }

  /** preview 必须跨 wire 保留；解码结果不携带任何 daemon 本地事实。 */
  @Test
  void uploadedResourceRoundTripsPreview() {
    byte[] data = "hello world\nsecond line\n".getBytes(StandardCharsets.UTF_8);
    UUID uploadId = UUID.randomUUID();
    ResourceResultContent content =
        new ResourceResultContent(
            uploadedRef(uploadId, "text/plain", "log.txt", data), "hello world\n", null);
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult("call-rt", List.of(content), false, "{}");

    String payload = codec.encodeCompleted(result, 1024, NoopUploader.INSTANCE);
    assertTrue(payload.contains("\"preview\":\"hello world\\n\""));

    EnvironmentCapabilityResult decoded =
        codec.decodeCompletedForInvocation(payload, "call-rt", 1024);
    ResourceResultContent decodedContent = (ResourceResultContent) decoded.contents().get(0);
    assertEquals(uploadId, decodedContent.resource().blobUploadId());
    assertEquals("hello world\n", decodedContent.preview());
    assertNull(decodedContent.textMetadata());
  }

  /** PROGRESS 编码只接受 text/json；resource/binary 在任何上传之前被拒绝（NoopUploader 遇调用即失败）。 */
  @Test
  void encodeProgressAcceptsTextAndJsonAndRejectsResourceOrBinaryBeforeAnyUpload() {
    EnvironmentCapabilityResult partial =
        new EnvironmentCapabilityResult(
            "p", List.of(new TextResultContent("hi"), new JsonResultContent("[1]")), false, "{}");
    String payload = codec.encodeProgress(partial);
    assertTrue(payload.contains("\"type\":\"text\""));
    assertTrue(payload.contains("\"type\":\"json\""));
    assertEquals(2, codec.decodeResult(payload).contents().size());

    byte[] data = new byte[] {1};
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.encodeProgress(
                new EnvironmentCapabilityResult(
                    "p",
                    List.of(
                        new ResourceResultContent(
                            uploadedRef(UUID.randomUUID(), "text/plain", null, data))),
                    false,
                    "{}")));
    assertThrows(
        DaemonProtocolException.class,
        () ->
            codec.encodeProgress(
                new EnvironmentCapabilityResult(
                    "p", List.of(new BinaryResultContent("text/plain", data)), false, "{}")));
  }

  /** COMPLETED 编码预检先于一切上传：后置条目超预算时不得产生任何上传副作用。 */
  @Test
  void encodeCompletedPreflightsAllContentsBeforeAnyUpload() {
    EnvironmentCapabilityResult laterOverBudgetBinary =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new TextResultContent("ok"),
                new BinaryResultContent("application/octet-stream", new byte[5])),
            false,
            "{}");
    DaemonProtocolException binaryError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(laterOverBudgetBinary, 4, NoopUploader.INSTANCE));
    assertTrue(binaryError.getMessage().contains("aggregate resource bytes exceed"));

    EnvironmentCapabilityResult laterOverBudgetResource =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new TextResultContent("ok"),
                new ResourceResultContent(
                    new ResourceRef(
                        ResourceRef.blobUploadUri(UUID.randomUUID()),
                        "text/plain",
                        null,
                        5L,
                        sha256Hex(new byte[5])))),
            false,
            "{}");
    DaemonProtocolException resourceError =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(laterOverBudgetResource, 4, NoopUploader.INSTANCE));
    assertTrue(resourceError.getMessage().contains("aggregate resource bytes exceed"));

    // 聚合超限：两个 3 字节 binary 在 4 字节预算下，第二个条目在预检阶段触发聚合错误。
    EnvironmentCapabilityResult aggregateOver =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new BinaryResultContent("application/octet-stream", new byte[3]),
                new BinaryResultContent("application/octet-stream", new byte[3])),
            false,
            "{}");
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.encodeCompleted(aggregateOver, 4, NoopUploader.INSTANCE));
  }

  /** binary 内容在终态编码阶段经上传端口直传，编码结果只保留返回引用。 */
  @Test
  void encodeCompletedUploadsBinaryContentAndEmitsUploadId() {
    byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
    UUID uploadId = UUID.randomUUID();
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "c", List.of(new BinaryResultContent("text/plain", data)), false, "{}");

    List<String> uploadedMediaTypes = new ArrayList<>();
    String payload =
        codec.encodeCompleted(
            result,
            1024,
            (invocationId, mediaType, name, bytes) -> {
              uploadedMediaTypes.add(mediaType);
              return uploadedRef(uploadId, mediaType, name, bytes);
            });

    assertEquals(List.of("text/plain"), uploadedMediaTypes);
    assertEquals(
        Set.of("type", "uploadId", "mediaType", "name", "size", "sha256"),
        resourceWireFields(payload));
    assertEquals(
        uploadId,
        ((ResourceResultContent) codec.decodeResult(payload).contents().get(0))
            .resource()
            .blobUploadId());
  }

  /** 未上传的本地 resource 引用绝不能进入终态：wire 只承载全局上传 id。 */
  @Test
  void encodeRejectsResourceRefThatWasNeverUploaded() {
    ResourceRef local =
        new ResourceRef("https://example.com/a", "text/plain", null, 3L, sha256Hex(new byte[3]));
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "c", List.of(new ResourceResultContent(local)), false, "{}");
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(result, 1024, NoopUploader.INSTANCE));
    assertTrue(error.getMessage().contains("must be uploaded to global storage"));
  }

  /** 上传端口的失败必须收敛为无敏感信息的协议错误。 */
  @Test
  void encodeCompletedConvergesUploadFailureToProtocolError() {
    byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult(
            "c", List.of(new BinaryResultContent("text/plain", data)), false, "{}");
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.encodeCompleted(
                    result,
                    1024,
                    (invocationId, mediaType, name, bytes) -> {
                      throw new IOException("https://minio.local/bucket?X-Amz-Signature=secret");
                    }));
    assertTrue(error.getMessage().contains("cannot upload resource content"));
    assertNoThrowableMessageContains(error, "X-Amz-Signature");
  }

  /** 最终 payload 必须 ≤ 16 MiB UTF-8：即使 binary 位于超大文本之前，也必须在任何上传前拒绝。 */
  @Test
  void encodeCompletedRejectsOversizedPayload() {
    EnvironmentCapabilityResult huge =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new BinaryResultContent("application/octet-stream", new byte[] {1}),
                new TextResultContent(
                    "a".repeat(DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES))),
            false,
            "{}");
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () -> codec.encodeCompleted(huge, 1024, NoopUploader.INSTANCE));
    assertTrue(error.getMessage().contains("payload exceeds"));
  }

  /** COMPLETED 编码的预算参数必须为正。 */
  @Test
  void encodeCompletedValidatesBudget() {
    EnvironmentCapabilityResult text =
        new EnvironmentCapabilityResult("c", List.of(new TextResultContent("x")), false, "{}");
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encodeCompleted(text, 0, NoopUploader.INSTANCE));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.encodeCompleted(text, -1, NoopUploader.INSTANCE));
    assertThrows(
        NullPointerException.class, () -> codec.encodeCompleted(null, 1024, NoopUploader.INSTANCE));
  }

  /** detailsJson 为 null 时编码为空对象 details。 */
  @Test
  void encodesNullDetailsJsonAsEmptyDetailsObject() {
    EnvironmentCapabilityResult result =
        new EnvironmentCapabilityResult("c", List.of(new TextResultContent("hi")), false, null);
    assertTrue(
        codec.encodeCompleted(result, 1024, NoopUploader.INSTANCE).contains("\"details\":{}"));
  }

  /** PROGRESS 解码拒绝 resource；COMPLETED 接受。 */
  @Test
  void progressDecodeRejectsResourceContent() {
    String payload = wire(resourceSegment(UUID.randomUUID(), 1L, sha256Hex(new byte[] {0x61})));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeProgressForInvocation(payload, "c", 1024));
    assertEquals(1, codec.decodeCompletedForInvocation(payload, "c", 1024).contents().size());
  }

  /** 单条声明 size 超限必须在任何分配前拒绝。 */
  @Test
  void rejectsOversizedDeclaredResourceAgainstBudget() {
    byte[] data = "abcdefgh".getBytes(StandardCharsets.UTF_8);
    String payload = wire(resourceSegment(UUID.randomUUID(), (long) data.length, sha256Hex(data)));
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeCompletedForInvocation(payload, "c", 4));
  }

  /** 聚合声明字节超限：两个条目各自在预算内但合计超出时拒绝。 */
  @Test
  void rejectsAggregateDeclaredResourceOverBudget() {
    String first = resourceSegment(UUID.randomUUID(), 3L, sha256Hex(new byte[3]));
    String second = resourceSegment(UUID.randomUUID(), 3L, sha256Hex(new byte[3]));
    String payload = wire(first + "," + second);
    assertThrows(
        DaemonProtocolException.class, () -> codec.decodeCompletedForInvocation(payload, "c", 5));
    assertEquals(2, codec.decodeCompletedForInvocation(payload, "c", 6).contents().size());
  }

  /** contents 元素数上限 64，防止内容列表无限放大。 */
  @Test
  void rejectsMoreThanSixtyFourContents() {
    StringBuilder contents = new StringBuilder();
    for (int index = 0; index < EnvironmentCapabilityResult.MAX_CONTENT_ITEMS + 1; index++) {
      if (contents.length() > 0) {
        contents.append(',');
      }
      contents.append("{\"type\":\"text\",\"text\":\"x\"}");
    }
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},\"contents\":["
                        + contents
                        + "]}}"));
    assertTrue(error.getMessage().contains("must not exceed"));
  }

  /** resource 的 uploadId 必须是规范小写 UUID；缺失、非 UUID、大写或 nil 之外的额外成分都被拒绝。 */
  @Test
  void rejectsMalformedUploadId() {
    String sha = sha256Hex(new byte[] {0x61});
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,"
                + "\"sha256\":\""
                + sha
                + "\"}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\"not-a-uuid\",\"mediaType\":\"text/plain\","
                + "\"name\":null,\"size\":1,\"sha256\":\""
                + sha
                + "\"}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + UUID.randomUUID().toString().toUpperCase()
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
                + sha
                + "\"}"));
  }

  /** wire 不再接受任何旧字段：未知字段一律拒绝。 */
  @Test
  void rejectsLegacyUriAndBase64Fields() {
    String sha = sha256Hex(new byte[] {0x61});
    String uploadId = UUID.randomUUID().toString();
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"uri\":\"file:///export/x\",\"mediaType\":\"text/plain\",\"name\":null,"
                + "\"size\":1,\"sha256\":\""
                + sha
                + "\"}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
                + sha
                + "\",\"legacyContentBytes\":\"YQ==\"}"));
  }

  /** size/sha256 对每个 wire resource 都是必填：缺失或 null 直接拒绝，杜绝绕过大小预检。 */
  @Test
  void rejectsResourceWithoutSizeOrSha() {
    String sha = sha256Hex(new byte[] {0x61});
    String uploadId = UUID.randomUUID().toString();
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"sha256\":\""
                + sha
                + "\"}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":null,\"sha256\":\""
                + sha
                + "\"}"));
  }

  /** 非法 sha256、负 size、非法 mediaType、空 name 都在 ResourceRef 构造期被拒绝。 */
  @Test
  void rejectsInvalidResourceFields() {
    String uploadId = UUID.randomUUID().toString();
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,"
                + "\"sha256\":\"!!!\"}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":-1,\"sha256\":\""
                + sha256Hex(new byte[] {0x61})
                + "\"}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"TEXT/PLAIN\",\"name\":null,\"size\":1,\"sha256\":\""
                + sha256Hex(new byte[] {0x61})
                + "\"}"));
    assertRejected(
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + uploadId
                + "\",\"mediaType\":\"text/plain\",\"name\":\"  \",\"size\":1,\"sha256\":\""
                + sha256Hex(new byte[] {0x61})
                + "\"}"));
  }

  /** resource name 必须满足 ResourceRef 规范，合法名可解码。 */
  @Test
  void decodesResourceNameUnderUtf8Cap() {
    String payload = wire(resourceSegmentWithName(UUID.randomUUID(), 1L, "my-file.txt"));
    assertEquals(1, codec.decodeResult(payload).contents().size());
    assertEquals(
        "my-file.txt",
        ((ResourceResultContent) codec.decodeResult(payload).contents().get(0)).resource().name());
  }

  /** preview 超限在解析期拒绝。 */
  @Test
  void rejectsOversizedPreview() {
    String preview = "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES + 1);
    String payload =
        wire(
            "{\"type\":\"resource\",\"uploadId\":\""
                + UUID.randomUUID()
                + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
                + sha256Hex(new byte[] {0x61})
                + "\",\"preview\":\""
                + preview
                + "\"}");
    assertRejected(payload);
  }

  /** Daemon Blob 数据面不接受未经内容复核的文本工件元数据，编码预检与严格解码都必须拒绝。 */
  @Test
  void rejectsTextArtifactMetadataBeforeUploadOrDecode() {
    TextArtifactMetadata metadata = new TextArtifactMetadata(1, 1);
    EnvironmentCapabilityResult binary =
        new EnvironmentCapabilityResult(
            "c",
            List.of(new BinaryResultContent("text/plain", new byte[] {0x61}, metadata)),
            false,
            "{}");
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.encodeCompleted(binary, 1024, NoopUploader.INSTANCE));

    EnvironmentCapabilityResult resource =
        new EnvironmentCapabilityResult(
            "c",
            List.of(
                new ResourceResultContent(
                    uploadedRef(UUID.randomUUID(), "text/plain", "log.txt", new byte[] {0x61}),
                    "a",
                    metadata)),
            false,
            "{}");
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.encodeCompleted(resource, 1024, NoopUploader.INSTANCE));

    String prefix =
        "{\"type\":\"resource\",\"uploadId\":\""
            + UUID.randomUUID()
            + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,\"sha256\":\""
            + sha256Hex(new byte[] {0x61})
            + "\"";
    assertRejected(wire(prefix + ",\"textMetadata\":{\"totalBytes\":1,\"totalLines\":1}}"));
  }

  /** 空字符串是合法文本内容，只要求类型是 string。 */
  @Test
  void emptyTextContentIsAllowed() {
    EnvironmentCapabilityResult decoded =
        codec.decodeResult(
            "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
                + "\"contents\":[{\"type\":\"text\",\"text\":\"\"}]}}");
    assertEquals("", ((TextResultContent) decoded.contents().get(0)).text());
  }

  /** 分相入口：PROGRESS 拒绝 resource；两者都校验空白期望 id。 */
  @Test
  void phaseSpecificDecodeEntryPointsValidateExpectations() {
    String textPayload =
        "{\"result\":{\"callId\":\"call-x\",\"error\":false,\"details\":{},"
            + "\"contents\":[{\"type\":\"text\",\"text\":\"ok\"}]}}";
    assertEquals("call-x", codec.decodeProgressForInvocation(textPayload, "call-x", 1024).callId());
    assertEquals(
        "call-x", codec.decodeCompletedForInvocation(textPayload, "call-x", 1024).callId());
    assertThrows(
        DaemonProtocolException.class,
        () -> codec.decodeCompletedForInvocation(textPayload, "other", 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodeProgressForInvocation(textPayload, " ", 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> codec.decodeCompletedForInvocation(textPayload, null, 1024));
  }

  /** 通用解码入口默认允许 resource 且不施加额外预算。 */
  @Test
  void genericDecodeEntryPointAllowsResources() {
    String payload = wire(resourceSegment(UUID.randomUUID(), 1L, sha256Hex(new byte[] {0x61})));
    assertEquals(1, codec.decodeResult(payload).contents().size());
  }

  /** uploadIdOf 只识别规范 blob-upload 引用。 */
  @Test
  void uploadIdOfRecognizesCanonicalReferencesOnly() {
    UUID uploadId = UUID.randomUUID();
    assertEquals(
        uploadId,
        DaemonCapabilityResultCodec.uploadIdOf(
            uploadedRef(uploadId, "text/plain", null, new byte[1])));
    assertEquals(
        null,
        DaemonCapabilityResultCodec.uploadIdOf(
            new ResourceRef(
                "https://example.com/a", "text/plain", null, 1L, sha256Hex(new byte[1]))));
  }

  /** 已知敏感串绝不进入任何异常消息链。 */
  @Test
  void errorMessagesNeverCarrySensitiveFragments() {
    String sensitive = "X-Amz-Signature";
    DaemonProtocolException error =
        assertThrows(
            DaemonProtocolException.class,
            () ->
                codec.decodeResult(
                    wire(
                        "{\"type\":\"resource\",\"uploadId\":\""
                            + UUID.randomUUID()
                            + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":1,"
                            + "\"sha256\":\""
                            + SHA_HELLO
                            + "\",\"legacyContentBytes\":\""
                            + sensitive
                            + "\"}")));
    assertNoThrowableMessageContains(error, sensitive);
  }

  /** 解析一条 COMPLETED payload 中第一个 resource content 的字段名集合，用于断言精确的 wire 形状。 */
  private static Set<String> resourceWireFields(String payload) {
    try {
      JsonNode contents = new ObjectMapper().readTree(payload).path("result").path("contents");
      for (JsonNode content : contents) {
        if ("resource".equals(content.path("type").asText())) {
          Set<String> fields = new HashSet<>();
          content.fieldNames().forEachRemaining(fields::add);
          return fields;
        }
      }
    } catch (Exception error) {
      throw new AssertionError(error);
    }
    throw new AssertionError("payload has no resource content: " + payload);
  }

  private void assertRejected(String payload) {
    assertThrows(DaemonProtocolException.class, () -> codec.decodeResult(payload));
  }

  private static String resourceSegment(UUID uploadId, Long size, String sha256) {
    return "{\"type\":\"resource\",\"uploadId\":\""
        + uploadId
        + "\",\"mediaType\":\"text/plain\",\"name\":null,\"size\":"
        + size
        + ",\"sha256\":\""
        + sha256
        + "\"}";
  }

  private static String resourceSegmentWithName(UUID uploadId, Long size, String name) {
    return "{\"type\":\"resource\",\"uploadId\":\""
        + uploadId
        + "\",\"mediaType\":\"text/plain\",\"name\":\""
        + name
        + "\",\"size\":"
        + size
        + ",\"sha256\":\""
        + sha256Hex(new byte[] {0x61})
        + "\"}";
  }

  private static String wire(String content) {
    return "{\"result\":{\"callId\":\"c\",\"error\":false,\"details\":{},"
        + "\"contents\":["
        + content
        + "]}}";
  }

  private static ResourceRef uploadedRef(
      UUID uploadId, String mediaType, String name, byte[] bytes) {
    return new ResourceRef(
        ResourceRef.blobUploadUri(uploadId),
        mediaType,
        name,
        (long) bytes.length,
        sha256Hex(bytes));
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static void assertNoThrowableMessageContains(Throwable error, String sensitive) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      assertFalse(message != null && message.contains(sensitive));
      current = current.getCause();
    }
  }

  /** 未配置上传时任何 resource/binary 内容都确定性失败；本测试只用它证明「未被调用」。 */
  private enum NoopUploader implements DaemonResourceUploader {
    INSTANCE;

    @Override
    public ResourceRef upload(String invocationId, String mediaType, String name, byte[] bytes) {
      throw new AssertionError("uploader must not be called");
    }
  }
}
