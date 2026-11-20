package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.provider.ProviderImageBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 验证内置 {@code read} 的档位冻结、Provider 投影和图片物化。物化器默认使用 720P，确保 1080P 来自工具历史。 */
class ReadImageTierProviderInputTest {

  private static final UUID BLOB_ID = new UUID(0L, 91L);
  private static final UUID ENVIRONMENT_UUID = new UUID(0L, 92L);
  private static final String PNG = "image/png";
  private static final String SHA256 = "b".repeat(64);
  private static final String PREVIEW = "synthetic screenshot";
  private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
  private static final Set<ModelInputModality> IMAGE_INPUT =
      Set.of(ModelInputModality.TEXT, ModelInputModality.IMAGE);

  /** 协议在用户位置与工具结果位置都接受图片：工具结果媒体走原生位置，不被推迟为后续 USER 消息。 */
  private static final ProviderMediaCapabilities IMAGE_IN_TOOL_RESULTS =
      new ProviderMediaCapabilities(
          Set.of(ModelInputModality.IMAGE), Set.of(ModelInputModality.IMAGE));

  /** 会话资源截屏（2560x1440）经 read 送达 Provider：冻结 1080P，内联 PNG 恰好落在 1920x1080。 */
  @Test
  void readSessionResourceScreenshotIsDeliveredInsideThe1080PBox() {
    byte[] screenshot = TestImages.screenshotPng(2560, 1440);

    ProviderInput input = providerInput(readInvocation(screenshot), screenshot);

    assertEquals(ImageInputTier.P1080, input.durable().imageTier());
    assertEquals("screenshot.png", input.durable().name());
    assertTrue(
        input.label().text().startsWith("Image: resource://files/" + BLOB_ID),
        input.label().text());
    assertTrue(input.label().text().contains("Original size: 2560 x 1440"), input.label().text());
    assertTrue(input.label().text().contains("Display size: 1920 x 1080"), input.label().text());
    BufferedImage delivered = decode(input.image());
    assertEquals(1920, delivered.getWidth());
    assertEquals(1080, delivered.getHeight());
  }

  /** 已绑定 Environment 的 read 读取框内截屏（1440x900）：同样冻结 1080P，且字节逐字节直通、不重新编码。 */
  @Test
  void environmentReadScreenshotAlreadyInsideTheTierIsDeliveredUnchanged() {
    byte[] screenshot = TestImages.screenshotPng(1440, 900);

    ProviderInput input = providerInput(environmentReadInvocation(screenshot), screenshot);

    assertEquals(ImageInputTier.P1080, input.durable().imageTier());
    assertEquals(
        "data:image/png;base64," + Base64.getEncoder().encodeToString(screenshot),
        input.image().source(),
        "已在 1080P 档位框内的 Environment 截屏必须逐字节直通");
    BufferedImage delivered = decode(input.image());
    assertEquals(1440, delivered.getWidth());
    assertEquals(900, delivered.getHeight());
  }

  /** 非内置的同名工具不享受新档位：同一张截屏仍缩到 1280x720。 */
  @Test
  void sameNamedPluginToolKeepsThe720PTier() {
    byte[] screenshot = TestImages.screenshotPng(2560, 1440);
    ToolBinding pluginImageTool =
        binding("read", "test-plugin", "read", EnvironmentSupport.NONE, null);

    ProviderInput input =
        providerInput(succeededInvocation(pluginImageTool, screenshot), screenshot);

    assertEquals(ImageInputTier.P720, input.durable().imageTier());
    BufferedImage delivered = decode(input.image());
    assertEquals(1280, delivered.getWidth());
    assertEquals(720, delivered.getHeight());
  }

  // ---------- 测试基座 ----------

  /** 无 Environment 的 read：Platform 读取会话资源，结果以同一 Session 资源回到 durable 历史。 */
  private static ToolInvocation readInvocation(byte[] image) {
    return succeededInvocation(
        binding("read", "builtin", "read", EnvironmentSupport.ROUTED, null), image);
  }

  /** 已绑定 Environment 的 read：文件由 Environment 读取并暂存为同一 Session 资源。 */
  private static ToolInvocation environmentReadInvocation(byte[] image) {
    return succeededInvocation(
        binding(
            "read",
            "builtin",
            "read",
            EnvironmentSupport.ROUTED,
            new EnvironmentId(ENVIRONMENT_UUID)),
        image);
  }

  /** 走完 mapper → Provider 投影 → attempt 物化，返回 durable 图片内容与本次 attempt 的真实 tool result。 */
  private static ProviderInput providerInput(ToolInvocation invocation, byte[] image) {
    MessagePayload payload = new HistoryPayloadMapper().toolResultPayload(invocation);
    AgentMessage message = payload.message();
    ToolResultMessageContent result =
        assertInstanceOf(ToolResultMessageContent.class, message.contents().get(0));
    ResourceMessageContent durable =
        assertInstanceOf(ResourceMessageContent.class, result.contents().get(0));
    ProviderToolResultBlock toolResult =
        assertInstanceOf(
            ProviderToolResultBlock.class,
            materializer(image)
                .materialize(
                    new ProviderMessageProjector().project(List.of(message)),
                    IMAGE_INPUT,
                    IMAGE_IN_TOOL_RESULTS)
                .get(0)
                .contents()
                .get(0));
    return new ProviderInput(durable, toolResult);
  }

  /** attempt 物化基座：单一 ACTIVE 图片 Blob + 固定 720P 默认档位，确保 1920 宽产物只能来自 durable 冻结值。 */
  private static ProviderResourceMaterializer materializer(byte[] image) {
    BufferedImage source = TestImages.decode(PNG, image);
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB_ID);
    blob.setMediaType(PNG);
    blob.setSizeBytes(image.length);
    blob.setWidth((long) source.getWidth());
    blob.setHeight((long) source.getHeight());
    blob.setState(StorageBlobState.ACTIVE);
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(blob);
    StorageBlobContentService contentService = mock(StorageBlobContentService.class);
    when(contentService.readBlobContent(eq(BLOB_ID), anyLong()))
        .thenReturn(new StorageBlobContent(BLOB_ID, image, PNG, image.length));
    return new ProviderResourceMaterializer(blobManager, contentService, () -> ImageInputTier.P720);
  }

  private static ToolInvocation succeededInvocation(ToolBinding binding, byte[] image) {
    ResourceRef ref =
        new ResourceRef(
            ResourceRef.sessionResourceUri(BLOB_ID),
            PNG,
            "screenshot.png",
            (long) image.length,
            SHA256);
    ToolResult result =
        new ToolResult("call-1", List.of(new ResourceResultContent(ref, PREVIEW)), false, "{}");
    return new ToolInvocation(
        new UUID(0L, 1L),
        new UUID(0L, 2L),
        new UUID(0L, 3L),
        new UUID(0L, 4L),
        0,
        new ToolCall("call-1", binding.descriptor().name(), "{}"),
        binding,
        ToolInvocationStatus.SUCCEEDED,
        1,
        ToolApproval.notRequired(),
        result,
        null,
        NOW,
        NOW);
  }

  private static ToolBinding binding(
      String toolName,
      String contributorId,
      String localName,
      EnvironmentSupport environmentSupport,
      EnvironmentId environmentId) {
    return new ToolBinding(
        new AgentToolDefinition(descriptor(toolName), ToolVisibility.SELECTABLE),
        new ContributorBinding(contributorId, localName),
        environmentSupport,
        environmentId,
        environmentId == null ? null : "dev");
  }

  private static ToolDescriptor descriptor(String name) {
    return new ToolDescriptor(
        name,
        "desc",
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static BufferedImage decode(ProviderImageBlock image) {
    String source = image.source();
    return TestImages.decode(
        image.mediaType(),
        Base64.getDecoder().decode(source.substring(source.indexOf(";base64,") + 8)));
  }

  /** 单次 attempt 的 Provider 输入：durable 图片内容与物化后的工具结果（文本标签 + 真实 PNG）。 */
  private record ProviderInput(ResourceMessageContent durable, ProviderToolResultBlock toolResult) {

    ProviderTextBlock label() {
      return assertInstanceOf(ProviderTextBlock.class, toolResult.contents().get(0));
    }

    ProviderImageBlock image() {
      return assertInstanceOf(ProviderImageBlock.class, toolResult.contents().get(1));
    }
  }
}
