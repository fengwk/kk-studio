package fun.fengwk.kkstudio.plugin.canvasmedia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasJson.JsonObject;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionArgsSchema;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenReference;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CanvasMediaFunctionAdapter 单元测试。
 *
 * <p>涵盖定义声明、严格 Schema 校验与默认值补齐、Preflight 边界校验、真实内存图像裁剪执行、取消与 Checkpoint 行为。
 */
class CanvasMediaFunctionAdapterTest {

  private final CanvasMediaFunctionAdapter adapter = new CanvasMediaFunctionAdapter();

  /** 验证适配器对外暴露的函数定义、启用状态及不可用原因符合契约。 */
  @Test
  void declaresExpectedFunctionDefinition() {
    assertTrue(adapter.enabled(), "适配器必须默认启用");
    assertNull(adapter.unavailableReason(), "启用状态下不可用原因必须为 null");

    List<CanvasFunctionDefinition> functions = adapter.functions();
    assertEquals(1, functions.size(), "必须声明且仅声明 1 个函数");

    CanvasFunctionDefinition definition = functions.get(0);
    assertEquals("image.crop", definition.name());
    assertEquals(
        List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE)), definition.outputs());
    assertNotNull(definition.description());
    assertThat(definition.description()).isNotBlank();

    CanvasFunctionReferencePolicy policy = definition.referencePolicy();
    assertEquals(Set.of(CanvasResourceKind.IMAGE), policy.allowedKinds());
    assertEquals(1, policy.maxReferences());
    assertEquals(Map.of(), policy.maxByKind());
  }

  /** 验证严格 Args Schema 符合规范，支持正确解析并补齐 x/y 默认值。 */
  @Test
  void schemaValidatesAndNormalizesArgsWithDefaults() {
    CanvasFunctionDefinition definition = adapter.functions().get(0);
    assertDoesNotThrow(
        () -> CanvasFunctionArgsSchema.validate(definition.argsSchema()),
        "函数声明的 argsSchema 必须通过严格校验");

    UUID sourceNodeId = UUID.randomUUID();
    JsonObject rawArgs =
        CanvasJson.parseObject(
            String.format(
                "{\"source\":{\"type\":\"resource\",\"nodeId\":\"%s\",\"index\":0},\"width\":100,\"height\":80}",
                sourceNodeId));

    JsonObject normalized =
        CanvasFunctionArgsSchema.normalize(rawArgs, definition.argsSchema(), "args");
    assertNotNull(normalized);
    assertEquals("0", normalized.values().get("x").write(), "未提供 x 时必须由 Schema 补齐默认值 0");
    assertEquals("0", normalized.values().get("y").write(), "未提供 y 时必须由 Schema 补齐默认值 0");
    assertEquals("100", normalized.values().get("width").write());
    assertEquals("80", normalized.values().get("height").write());
  }

  /** 验证严格 Schema 拒绝缺失必填字段、未知字段及非法类型。 */
  @Test
  void schemaRejectsInvalidInputs() {
    CanvasFunctionDefinition definition = adapter.functions().get(0);
    UUID sourceNodeId = UUID.randomUUID();

    // 缺少必填字段 width
    JsonObject missingWidth =
        CanvasJson.parseObject(
            String.format(
                "{\"source\":{\"type\":\"resource\",\"nodeId\":\"%s\",\"index\":0},\"height\":80}",
                sourceNodeId));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.normalize(missingWidth, definition.argsSchema(), "args"),
        "缺少必填字段 width 必须抛出异常");

    // 缺少必填字段 source
    JsonObject missingSource = CanvasJson.parseObject("{\"width\":100,\"height\":80}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.normalize(missingSource, definition.argsSchema(), "args"),
        "缺少必填字段 source 必须抛出异常");

    // 包含未知额外字段
    JsonObject extraField =
        CanvasJson.parseObject(
            String.format(
                "{\"source\":{\"type\":\"resource\",\"nodeId\":\"%s\",\"index\":0},\"width\":100,\"height\":80,\"extra\":123}",
                sourceNodeId));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.normalize(extraField, definition.argsSchema(), "args"),
        "包含未声明属性必须因 additionalProperties=false 被拒绝");

    // width 越界 (minimum: 1)
    JsonObject zeroWidth =
        CanvasJson.parseObject(
            String.format(
                "{\"source\":{\"type\":\"resource\",\"nodeId\":\"%s\",\"index\":0},\"width\":0,\"height\":80}",
                sourceNodeId));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.normalize(zeroWidth, definition.argsSchema(), "args"),
        "width < 1 必须被拒绝");

    // x 越界 (minimum: 0)
    JsonObject negativeX =
        CanvasJson.parseObject(
            String.format(
                "{\"source\":{\"type\":\"resource\",\"nodeId\":\"%s\",\"index\":0},\"x\":-1,\"width\":100,\"height\":80}",
                sourceNodeId));
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasFunctionArgsSchema.normalize(negativeX, definition.argsSchema(), "args"),
        "x < 0 必须被拒绝");
  }

  /** 验证 Preflight 在冻结 Manifest 引用数量、类型或尺寸不合法时 fail closed。 */
  @Test
  void preflightRejectsInvalidManifest() {
    UUID targetId = UUID.randomUUID();
    JsonObject args = CanvasJson.parseObject("{\"x\":0,\"y\":0,\"width\":50,\"height\":50}");

    // 空 Manifest
    CanvasFunctionFrozenRun emptyManifestRun = createRun(args, List.of(), targetId);
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.preflight(emptyManifestRun),
        "Manifest 为空必须在 preflight 阶段拒绝");

    // 多个引用
    CanvasFunctionFrozenReference ref1 =
        createFrozenReference(CanvasResourceKind.IMAGE, 100L, 100L);
    CanvasFunctionFrozenReference ref2 =
        createFrozenReference(CanvasResourceKind.IMAGE, 100L, 100L);
    CanvasFunctionFrozenRun multiManifestRun = createRun(args, List.of(ref1, ref2), targetId);
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.preflight(multiManifestRun),
        "Manifest 超过 1 个引用必须在 preflight 阶段拒绝");

    // 引用种类不是 IMAGE
    CanvasFunctionFrozenReference videoRef =
        createFrozenReference(CanvasResourceKind.VIDEO, 100L, 100L);
    CanvasFunctionFrozenRun videoRun = createRun(args, List.of(videoRef), targetId);
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.preflight(videoRun),
        "Manifest 种类为非 IMAGE 时必须在 preflight 阶段拒绝");

    // 引用缺少宽高事实
    CanvasFunctionFrozenReference nullDimensionRef =
        createFrozenReference(CanvasResourceKind.IMAGE, null, null);
    CanvasFunctionFrozenRun nullDimRun = createRun(args, List.of(nullDimensionRef), targetId);
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.preflight(nullDimRun),
        "Manifest 缺少宽高事实时必须在 preflight 阶段拒绝");
  }

  /** 验证 Preflight 能够依据冻结宽高事实拒绝超出边界的裁剪参数。 */
  @Test
  void preflightRejectsOutOfBoundsCrop() {
    UUID targetId = UUID.randomUUID();
    CanvasFunctionFrozenReference ref = createFrozenReference(CanvasResourceKind.IMAGE, 100L, 80L);

    // x + width > 100
    JsonObject outOfX = CanvasJson.parseObject("{\"x\":60,\"y\":0,\"width\":50,\"height\":80}");
    CanvasFunctionFrozenRun outOfXRun = createRun(outOfX, List.of(ref), targetId);
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.preflight(outOfXRun),
        "x + width 超过图像宽度必须在 preflight 阶段拒绝");

    // y + height > 80
    JsonObject outOfY = CanvasJson.parseObject("{\"x\":0,\"y\":50,\"width\":100,\"height\":40}");
    CanvasFunctionFrozenRun outOfYRun = createRun(outOfY, List.of(ref), targetId);
    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.preflight(outOfYRun),
        "y + height 超过图像高度必须在 preflight 阶段拒绝");

    // 正好贴边：合法的最大范围
    JsonObject exactBounds =
        CanvasJson.parseObject("{\"x\":0,\"y\":0,\"width\":100,\"height\":80}");
    CanvasFunctionFrozenRun exactRun = createRun(exactBounds, List.of(ref), targetId);
    assertDoesNotThrow(() -> adapter.preflight(exactRun), "完全吻合图像尺寸的裁剪必须通过 preflight");
  }

  /** 验证 submit 与 cancel 均为安全的空操作，无需外部 I/O。 */
  @Test
  void submitAndCancelAreSafeNoOps() {
    FakeExecutionContext context = new FakeExecutionContext();
    CanvasFunctionFrozenReference ref = createFrozenReference(CanvasResourceKind.IMAGE, 100L, 80L);
    JsonObject args = CanvasJson.parseObject("{\"x\":0,\"y\":0,\"width\":50,\"height\":50}");
    CanvasFunctionFrozenRun run = createRun(args, List.of(ref), UUID.randomUUID());

    assertDoesNotThrow(() -> adapter.submit(context, run), "submit 必须安全返回且不抛出异常");
    assertDoesNotThrow(() -> adapter.cancel(run), "cancel 必须安全返回且不抛出异常");
  }

  /** 验证对真实内存 PNG 执行裁剪，断言像素、尺寸、物化格式与 Checkpoint 记录。 */
  @Test
  void executePerformsRealCropAndMaterializesPng() throws IOException {
    // 构造一张 100x80 的测试图片，4 个象限填充不同颜色
    int sourceWidth = 100;
    int sourceHeight = 80;
    BufferedImage testImage =
        new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2d = testImage.createGraphics();
    try {
      g2d.setColor(Color.RED);
      g2d.fillRect(0, 0, 50, 40);
      g2d.setColor(Color.GREEN);
      g2d.fillRect(50, 0, 50, 40);
      g2d.setColor(Color.BLUE);
      g2d.fillRect(0, 40, 50, 40);
      g2d.setColor(Color.YELLOW);
      g2d.fillRect(50, 40, 50, 40);
    } finally {
      g2d.dispose();
    }

    byte[] sourcePngBytes = encodeToPng(testImage);
    CanvasFunctionFrozenReference ref =
        createFrozenReference(CanvasResourceKind.IMAGE, (long) sourceWidth, (long) sourceHeight);

    FakeExecutionContext context = new FakeExecutionContext();
    context.putOriginal(ref, sourcePngBytes);

    UUID targetResourceId = UUID.randomUUID();
    // 裁剪右下角的黄色区域：x=50, y=40, width=30, height=20
    JsonObject args = CanvasJson.parseObject("{\"x\":50,\"y\":40,\"width\":30,\"height\":20}");
    CanvasFunctionFrozenRun run = createRun(args, List.of(ref), targetResourceId);

    List<UUID> results = adapter.execute(context, run);
    assertEquals(List.of(targetResourceId), results, "execute 必须返回物化的 targetResourceId");

    // 验证 Checkpoint
    assertEquals(1, context.checkpoints().size());
    FakeExecutionContext.CheckpointRecord checkpoint = context.checkpoints().get(0);
    assertEquals("CROPPING", checkpoint.stage());
    assertEquals(50, checkpoint.adapterState().get("x"));
    assertEquals(40, checkpoint.adapterState().get("y"));
    assertEquals(30, checkpoint.adapterState().get("width"));
    assertEquals(20, checkpoint.adapterState().get("height"));

    // 验证物化出来的 PNG 内容
    assertEquals(targetResourceId, context.materializedResourceId());
    byte[] materializedBytes = context.materializedBytes();
    assertNotNull(materializedBytes, "物化字节数组不能为空");

    BufferedImage croppedImage = ImageIO.read(new ByteArrayInputStream(materializedBytes));
    assertNotNull(croppedImage, "物化内容必须是有效图像");
    assertEquals(30, croppedImage.getWidth(), "裁剪后宽度必须为 30");
    assertEquals(20, croppedImage.getHeight(), "裁剪后高度必须为 20");

    // 验证裁剪区域的所有像素颜色均为黄色
    int expectedYellowRgb = Color.YELLOW.getRGB();
    for (int x = 0; x < 30; x++) {
      for (int y = 0; y < 20; y++) {
        assertEquals(
            expectedYellowRgb, croppedImage.getRGB(x, y), String.format("像素 (%d, %d) 必须为黄色", x, y));
      }
    }
  }

  /** 验证裁剪非透明 RGB 图片正确生成 PNG 并保持色彩。 */
  @Test
  void executePerformsCropOnOpaqueRgbImage() throws IOException {
    int sourceWidth = 40;
    int sourceHeight = 40;
    BufferedImage testImage =
        new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB);
    Graphics2D g2d = testImage.createGraphics();
    try {
      g2d.setColor(Color.CYAN);
      g2d.fillRect(0, 0, 40, 40);
    } finally {
      g2d.dispose();
    }

    byte[] sourcePngBytes = encodeToPng(testImage);
    CanvasFunctionFrozenReference ref =
        createFrozenReference(CanvasResourceKind.IMAGE, (long) sourceWidth, (long) sourceHeight);

    FakeExecutionContext context = new FakeExecutionContext();
    context.putOriginal(ref, sourcePngBytes);

    UUID targetResourceId = UUID.randomUUID();
    JsonObject args = CanvasJson.parseObject("{\"x\":5,\"y\":5,\"width\":10,\"height\":10}");
    CanvasFunctionFrozenRun run = createRun(args, List.of(ref), targetResourceId);

    List<UUID> results = adapter.execute(context, run);
    assertEquals(List.of(targetResourceId), results);

    BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(context.materializedBytes()));
    assertEquals(10, cropped.getWidth());
    assertEquals(10, cropped.getHeight());
    assertEquals(Color.CYAN.getRGB(), cropped.getRGB(0, 0));
  }

  /** 验证在 isRunning 为 false 时拒绝物化并抛出 IllegalStateException。 */
  @Test
  void executeAbortsWhenContextIsNotRunning() {
    BufferedImage testImage = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
    byte[] sourcePngBytes = encodeToPng(testImage);
    CanvasFunctionFrozenReference ref = createFrozenReference(CanvasResourceKind.IMAGE, 50L, 50L);

    FakeExecutionContext context = new FakeExecutionContext();
    context.putOriginal(ref, sourcePngBytes);
    context.setRunning(false);

    UUID targetResourceId = UUID.randomUUID();
    JsonObject args = CanvasJson.parseObject("{\"x\":0,\"y\":0,\"width\":20,\"height\":20}");
    CanvasFunctionFrozenRun run = createRun(args, List.of(ref), targetResourceId);

    assertThrows(
        IllegalStateException.class,
        () -> adapter.execute(context, run),
        "context.isRunning() 为 false 时必须抛出 IllegalStateException");
    assertNull(context.materializedBytes(), "停止运行时不得调用 materializeOutput");
  }

  /** 验证当输入流内容无法解码为图像时抛出 IllegalArgumentException。 */
  @Test
  void executeFailsOnCorruptImageData() {
    byte[] corruptBytes = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};
    CanvasFunctionFrozenReference ref = createFrozenReference(CanvasResourceKind.IMAGE, 50L, 50L);

    FakeExecutionContext context = new FakeExecutionContext();
    context.putOriginal(ref, corruptBytes);

    UUID targetResourceId = UUID.randomUUID();
    JsonObject args = CanvasJson.parseObject("{\"x\":0,\"y\":0,\"width\":20,\"height\":20}");
    CanvasFunctionFrozenRun run = createRun(args, List.of(ref), targetResourceId);

    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.execute(context, run),
        "非法图像数据必须抛出 IllegalArgumentException");
  }

  /** 验证当解码后的实际图像尺寸小于裁剪范围时抛出 IllegalArgumentException。 */
  @Test
  void executeFailsWhenDecodedDimensionsMismatchCrop() {
    // 实际图像为 30x30，但 manifest 声称 100x100
    BufferedImage smallImage = new BufferedImage(30, 30, BufferedImage.TYPE_INT_ARGB);
    byte[] smallBytes = encodeToPng(smallImage);
    CanvasFunctionFrozenReference ref = createFrozenReference(CanvasResourceKind.IMAGE, 100L, 100L);

    FakeExecutionContext context = new FakeExecutionContext();
    context.putOriginal(ref, smallBytes);

    UUID targetResourceId = UUID.randomUUID();
    // 裁剪 x=40, y=0, width=20, height=20 (超过实际 30 像素宽度)
    JsonObject outOfXArgs = CanvasJson.parseObject("{\"x\":40,\"y\":0,\"width\":20,\"height\":20}");
    CanvasFunctionFrozenRun outOfXRun = createRun(outOfXArgs, List.of(ref), targetResourceId);

    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.execute(context, outOfXRun),
        "解码图像宽度不足以满足裁剪区域时必须抛出异常");

    // 裁剪 x=0, y=40, width=20, height=20 (超过实际 30 像素高度)
    JsonObject outOfYArgs = CanvasJson.parseObject("{\"x\":0,\"y\":40,\"width\":20,\"height\":20}");
    CanvasFunctionFrozenRun outOfYRun = createRun(outOfYArgs, List.of(ref), targetResourceId);

    assertThrows(
        IllegalArgumentException.class,
        () -> adapter.execute(context, outOfYRun),
        "解码图像高度不足以满足裁剪区域时必须抛出异常");
  }

  /** 验证当读取或关闭源图片流抛出 IOException 时转换为 IllegalStateException。 */
  @Test
  void executeWrapsIoExceptionInIllegalStateException() {
    CanvasFunctionFrozenReference ref = createFrozenReference(CanvasResourceKind.IMAGE, 100L, 100L);
    CanvasFunctionExecutionContext brokenContext =
        new CanvasFunctionExecutionContext() {
          @Override
          public void checkpoint(String stage, Map<String, Object> adapterState) {}

          @Override
          public boolean isRunning() {
            return true;
          }

          @Override
          public CanvasFunctionResourceStream openOriginal(
              CanvasFunctionFrozenReference reference) {
            BufferedImage testImage = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
            byte[] validPng = encodeToPng(testImage);
            return new CanvasFunctionResourceStream(
                new ByteArrayInputStream(validPng),
                validPng.length,
                () -> {
                  throw new IOException("Simulated stream close failure");
                });
          }

          @Override
          public String presignOriginal(
              CanvasFunctionFrozenReference reference, long expiresSeconds) {
            return "https://example.com/broken";
          }

          @Override
          public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
            return output.resourceId();
          }

          @Override
          public UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text) {
            throw new UnsupportedOperationException();
          }
        };

    UUID targetResourceId = UUID.randomUUID();
    JsonObject args = CanvasJson.parseObject("{\"x\":0,\"y\":0,\"width\":20,\"height\":20}");
    CanvasFunctionFrozenRun run = createRun(args, List.of(ref), targetResourceId);

    assertThrows(
        IllegalStateException.class,
        () -> adapter.execute(brokenContext, run),
        "关闭或读取图像流失败时必须抛出 IllegalStateException");
  }

  /** 验证空指针与非法参数在 preflight/execute/CropRectangle 中的严密防守。 */
  @Test
  void defensiveChecksRejectNullsAndMalformedNumbers() {
    FakeExecutionContext context = new FakeExecutionContext();
    CanvasFunctionFrozenReference ref = createFrozenReference(CanvasResourceKind.IMAGE, 100L, 100L);
    JsonObject args = CanvasJson.parseObject("{\"x\":0,\"y\":0,\"width\":20,\"height\":20}");
    CanvasFunctionFrozenRun run = createRun(args, List.of(ref), UUID.randomUUID());

    assertThrows(NullPointerException.class, () -> adapter.preflight(null));
    assertThrows(NullPointerException.class, () -> adapter.execute(null, run));
    assertThrows(NullPointerException.class, () -> adapter.execute(context, null));

    // CropRectangle 参数提取异常分支校验
    assertThrows(
        NullPointerException.class, () -> CanvasMediaFunctionAdapter.CropRectangle.from(null));

    // 缺少必填字段 width
    JsonObject missingWidth = CanvasJson.parseObject("{\"height\":20}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasMediaFunctionAdapter.CropRectangle.from(missingWidth));

    // 缺少必填字段 height
    JsonObject missingHeight = CanvasJson.parseObject("{\"width\":20}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasMediaFunctionAdapter.CropRectangle.from(missingHeight));

    // 字段类型不是 JsonNumber
    JsonObject stringWidth = CanvasJson.parseObject("{\"width\":\"20\",\"height\":20}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasMediaFunctionAdapter.CropRectangle.from(stringWidth));

    // 小数导致 ArithmeticException
    JsonObject decimalWidth = CanvasJson.parseObject("{\"width\":20.5,\"height\":20}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasMediaFunctionAdapter.CropRectangle.from(decimalWidth));

    // 非法非正数
    JsonObject zeroHeight = CanvasJson.parseObject("{\"width\":20,\"height\":0}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasMediaFunctionAdapter.CropRectangle.from(zeroHeight));

    JsonObject negativeX = CanvasJson.parseObject("{\"x\":-5,\"width\":20,\"height\":20}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasMediaFunctionAdapter.CropRectangle.from(negativeX));

    JsonObject negativeY = CanvasJson.parseObject("{\"y\":-5,\"width\":20,\"height\":20}");
    assertThrows(
        IllegalArgumentException.class,
        () -> CanvasMediaFunctionAdapter.CropRectangle.from(negativeY));
  }

  private static byte[] encodeToPng(BufferedImage image) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ImageIO.write(image, "PNG", baos);
      return baos.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static CanvasFunctionFrozenReference createFrozenReference(
      CanvasResourceKind kind, Long width, Long height) {
    return new CanvasFunctionFrozenReference(
        UUID.randomUUID(),
        0,
        UUID.randomUUID(),
        UUID.randomUUID(),
        kind,
        "input.png",
        "image/png",
        1024L,
        width,
        height,
        null);
  }

  private static CanvasFunctionFrozenRun createRun(
      JsonObject args, List<CanvasFunctionFrozenReference> manifest, UUID targetResourceId) {
    CanvasMediaFunctionAdapter adapter = new CanvasMediaFunctionAdapter();
    CanvasFunctionDefinition definition = adapter.functions().get(0);
    List<CanvasFunctionFrozenOutput> outputs =
        List.of(
            new CanvasFunctionFrozenOutput(
                targetResourceId, 0, CanvasResourceKind.IMAGE, "cropped.png"));
    return new CanvasFunctionFrozenRun(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "CropNode",
        UUID.randomUUID(),
        definition,
        args,
        manifest,
        outputs,
        CanvasFunctionSubmitState.SUBMITTED,
        "PENDING",
        Map.of());
  }

  /** 验证媒体槽位拒绝 materializeTextOutput 调用并抛出 IllegalArgumentException。 */
  @Test
  void mediaSlotRejectsMaterializeTextOutput() {
    FakeExecutionContext context = new FakeExecutionContext();
    CanvasFunctionFrozenOutput mediaOutput =
        new CanvasFunctionFrozenOutput(
            UUID.randomUUID(), 0, CanvasResourceKind.IMAGE, "cropped.png");
    assertThrows(
        IllegalArgumentException.class,
        () -> context.materializeTextOutput(mediaOutput, "inline text"),
        "媒体槽位必须拒绝 materializeTextOutput");
  }

  static final class FakeExecutionContext implements CanvasFunctionExecutionContext {

    private boolean running = true;
    private final Map<UUID, byte[]> originalBlobs = new HashMap<>();
    private final List<CheckpointRecord> checkpoints = new ArrayList<>();
    private UUID materializedResourceId;
    private byte[] materializedBytes;

    void putOriginal(CanvasFunctionFrozenReference reference, byte[] bytes) {
      originalBlobs.put(reference.blobId(), bytes);
    }

    void setRunning(boolean running) {
      this.running = running;
    }

    @Override
    public void checkpoint(String stage, Map<String, Object> adapterState) {
      checkpoints.add(new CheckpointRecord(stage, adapterState));
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public CanvasFunctionResourceStream openOriginal(CanvasFunctionFrozenReference reference) {
      byte[] data = originalBlobs.get(reference.blobId());
      if (data == null) {
        throw new IllegalArgumentException("Blob not found: " + reference.blobId());
      }
      return new CanvasFunctionResourceStream(
          new ByteArrayInputStream(data), data.length, () -> {});
    }

    @Override
    public String presignOriginal(CanvasFunctionFrozenReference reference, long expiresSeconds) {
      return "https://example.com/" + reference.blobId();
    }

    @Override
    public UUID materializeOutput(CanvasFunctionFrozenOutput output, InputStream content) {
      if (output.inlineText()) {
        throw new IllegalArgumentException(
            "TEXT output must be materialized through materializeTextOutput");
      }
      try {
        this.materializedResourceId = output.resourceId();
        this.materializedBytes = content.readAllBytes();
        return output.resourceId();
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    @Override
    public UUID materializeTextOutput(CanvasFunctionFrozenOutput output, String text) {
      throw new IllegalArgumentException(
          "only a TEXT output slot can be materialized as inline text");
    }

    List<CheckpointRecord> checkpoints() {
      return checkpoints;
    }

    UUID materializedResourceId() {
      return materializedResourceId;
    }

    byte[] materializedBytes() {
      return materializedBytes;
    }

    record CheckpointRecord(String stage, Map<String, Object> adapterState) {}
  }
}
