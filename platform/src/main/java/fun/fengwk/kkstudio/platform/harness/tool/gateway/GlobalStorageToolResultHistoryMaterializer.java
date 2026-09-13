package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryContext;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.cloudfs.domain.CloudPath;
import fun.fengwk.kkstudio.platform.cloudfs.domain.ToolArtifactPath;
import fun.fengwk.kkstudio.platform.cloudfs.service.CloudArtifactService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobIngestService;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 基于全局 Blob 存储与 CFS Tool Artifact 挂载的 {@link ToolResultHistoryMaterializer}。
 *
 * <p>在 Tool outcome Entry 插入前、同一 store 事务内：
 *
 * <ul>
 *   <li>只通过注入的 Platform {@link ResourceStore} 读取其拥有的 Resource，绝不解析或访问任意外部 URI；
 *   <li>对文本工件严格复核 ref size/sha、UTF-8 编码合法性与 totalBytes/totalLines 计数；
 *   <li>摄入全局存储（{@code storage_blob}）并关联 session ref；
 *   <li>对文本工件在同一事务内调用 {@link CloudArtifactService#createToolArtifact} 挂载到 {@code
 *       /.artifacts/tool-results/{threadId}/{invocationId}.txt|json} 并独立 retain；
 *   <li>构造并返回携带完整结构化 facts 的 durable {@link ResourceMessageContent}；
 *   <li>任一项失败整体回滚事务，绝不产生部分 history。
 * </ul>
 */
public class GlobalStorageToolResultHistoryMaterializer implements ToolResultHistoryMaterializer {

  private final StorageBlobIngestService ingestService;
  private final CloudArtifactService cloudArtifactService;
  private final ResourceStore resourceStore;
  private final int resourceMaxBytes;

  public GlobalStorageToolResultHistoryMaterializer(
      StorageBlobIngestService ingestService,
      CloudArtifactService cloudArtifactService,
      ResourceStore resourceStore,
      int resourceMaxBytes) {
    this.ingestService = Objects.requireNonNull(ingestService, "ingestService");
    this.cloudArtifactService =
        Objects.requireNonNull(cloudArtifactService, "cloudArtifactService");
    this.resourceStore = Objects.requireNonNull(resourceStore, "resourceStore");
    if (resourceMaxBytes <= 0) {
      throw new IllegalArgumentException("resourceMaxBytes must be positive");
    }
    this.resourceMaxBytes = resourceMaxBytes;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<AgentMessageContent> materialize(
      ToolResultHistoryContext context, ToolResult result) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(result, "result");
    List<ResultContent> source = result.contents();
    ResolvedResource[] resolvedResources = new ResolvedResource[source.size()];
    for (int index = 0; index < source.size(); index++) {
      ResultContent content = source.get(index);
      if (content instanceof BinaryResultContent) {
        throw new IllegalArgumentException(
            "binary tool content must be finalized before history materialization");
      }
      if (content instanceof ResourceResultContent resource) {
        resolvedResources[index] = resolveResource(resource);
      }
    }

    List<AgentMessageContent> contents = new ArrayList<>(source.size());
    for (int index = 0; index < source.size(); index++) {
      ResultContent content = source.get(index);
      if (content instanceof TextResultContent text) {
        contents.add(new TextMessageContent(text.text()));
      } else if (content instanceof JsonResultContent json) {
        contents.add(new JsonMessageContent(json.json()));
      } else if (content instanceof ResourceResultContent resource) {
        contents.add(ingestResource(context, index + 1, resource, resolvedResources[index]));
      } else {
        throw new IllegalArgumentException(
            "unsupported tool content kind for history materialization: "
                + content.getClass().getSimpleName());
      }
    }
    return List.copyOf(contents);
  }

  private ResourceMessageContent ingestResource(
      ToolResultHistoryContext context,
      int index,
      ResourceResultContent resource,
      ResolvedResource resolved) {
    ResourceRef ref = resource.resource();
    byte[] bytes = resolved.bytes();

    if (resource.textMetadata() != null) {
      UUID blobId = ingestService.ingest(context.sessionId(), bytes, ref.mediaType());
      String ext = "application/json".equals(ref.mediaType()) ? "json" : "txt";
      cloudArtifactService.createToolArtifact(
          context.threadId(), context.invocationId(), ext, blobId);
      CloudPath artifactPath =
          ToolArtifactPath.format(context.threadId(), context.invocationId(), ext);
      String name = ref.name() == null ? context.toolName() + "-result." + ext : ref.name();
      return ResourceMessageContent.artifact(
          blobId,
          name,
          artifactPath.toString(),
          bytes.length,
          resolved.totalLines(),
          resolved.preview());
    }

    UUID blobId = ingestService.ingest(context.sessionId(), bytes, ref.mediaType());
    String name = ref.name() == null ? "resource-" + index : ref.name();
    return ResourceMessageContent.media(blobId, name, resource.preview());
  }

  private ResolvedResource resolveResource(ResourceResultContent resource) {
    ResourceRef ref = resource.resource();
    if (ref.size() == null || ref.sha256() == null) {
      throw new IllegalArgumentException("managed resource must declare size and sha256");
    }
    if (ref.size() > resourceMaxBytes) {
      throw new IllegalArgumentException(
          "managed resource must not exceed " + resourceMaxBytes + " bytes");
    }

    byte[] bytes;
    try {
      bytes = resourceStore.read(ref);
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("managed resource content is unavailable");
    }
    if (bytes == null
        || bytes.length != ref.size()
        || bytes.length > resourceMaxBytes
        || !ref.sha256().equals(sha256Hex(bytes))) {
      throw new IllegalArgumentException("managed resource content failed integrity validation");
    }

    if (resource.textMetadata() == null) {
      return new ResolvedResource(bytes, null, null);
    }
    String text;
    try {
      CharsetDecoder decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalid) {
      throw new IllegalArgumentException("text artifact content must be valid UTF-8");
    }
    long totalLines = ToolResultFinalizer.countPhysicalLines(text);
    if (resource.textMetadata().totalBytes() != bytes.length
        || resource.textMetadata().totalLines() != totalLines) {
      throw new IllegalArgumentException("text artifact metadata failed integrity validation");
    }
    return new ResolvedResource(bytes, ToolResultFinalizer.extractRawPreview(text), totalLines);
  }

  private record ResolvedResource(byte[] bytes, String preview, Long totalLines) {
    private ResolvedResource {
      bytes = Objects.requireNonNull(bytes, "bytes");
      if ((preview == null) != (totalLines == null)) {
        throw new IllegalArgumentException(
            "preview and totalLines must either both be present or both be absent");
      }
    }
  }

  private static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(content);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available", error);
    }
  }
}
