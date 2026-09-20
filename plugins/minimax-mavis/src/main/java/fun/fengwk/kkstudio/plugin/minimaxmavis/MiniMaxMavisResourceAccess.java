package fun.fengwk.kkstudio.plugin.minimaxmavis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginMediaFamily;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceUnavailableException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 媒体输入解析与生成结果暂存。
 *
 * <p>模型参数的资源约定是「公开 HTTP(S) URL 或当前 Session 有权访问的 {@code kkstudio:/resources/<blobId>}」。公开 URL 原样交给
 * Provider；{@code kkstudio:} 资源必须经 {@link PluginResourceGateway} 解析成受控短期 HTTPS 地址，因此 Plugin 自己从不读取
 * Resource 字节，也不会把 Session 凭据或本地路径交给第三方。
 *
 * <p>生成类与下载类能力的响应里只有第三方临时地址：这些地址会被 {@link #stageOutputs} 逐个流式暂存为全局 Blob 上传引用 （{@code
 * blob-upload:<uploadId>}），历史里只留下稳定 Resource，绝不保留临时 CDN URL。
 *
 * <p>没有任何网关绑定时，需要资源或需要暂存媒体的调用在发送请求之前就确定性失败，绝不消费额度后再伪装成功。
 */
public final class MiniMaxMavisResourceAccess {

  /** 会话 Resource URI 的严格形态：scheme + 固定 path + 规范小写 UUID。 */
  private static final Pattern SESSION_RESOURCE =
      Pattern.compile(
          "kkstudio:/resources/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

  private static final String SESSION_RESOURCE_SCHEME = "kkstudio:";

  /** 单次调用最多暂存的媒体数量；超出的响应视为对静态 Tool 契约的违背。 */
  private static final int MAX_STAGED_MEDIA = 8;

  private static final String HTTPS_SCHEME = "https";

  private final PluginResourceGateway gateway;

  public MiniMaxMavisResourceAccess(PluginResourceGateway gateway) {
    this.gateway = gateway;
  }

  /** 本部署是否绑定了资源网关。 */
  public boolean gatewayBound() {
    return gateway != null;
  }

  /**
   * 把参数里的 {@code kkstudio:} 资源就地解析为受控短期 URL，其余取值原样保留。
   *
   * @throws PluginResourceUnavailableException 需要资源但没有网关，或资源无法在契约内解析
   */
  public JsonNode resolveInputs(JsonNode arguments) {
    if (!containsSessionResource(arguments)) {
      return arguments;
    }
    requireGateway("the request references a session resource");
    return resolve(arguments);
  }

  /**
   * 把响应里的第三方媒体逐个暂存为 Blob 上传引用。
   *
   * @return 稳定 Resource 引用列表；响应中没有可用的绝对 URL 时返回空列表
   */
  public List<ResourceRef> stageOutputs(
      JsonNode payload, MavisCapability capability, String toolCallId) {
    List<URI> mediaUris = mediaUris(payload);
    if (mediaUris.isEmpty()) {
      return List.of();
    }
    requireGateway("the response carries media");
    PluginMediaFamily family = mediaFamily(capability);
    List<ResourceRef> staged = new ArrayList<>(mediaUris.size());
    for (int index = 0; index < mediaUris.size(); index++) {
      staged.add(
          gateway.stageRemoteMedia(
              mediaUris.get(index),
              family,
              toolCallId + "-" + capability.id() + "-" + (index + 1)));
    }
    return staged;
  }

  /**
   * 深度优先收集响应中的绝对 HTTPS URL，按文档顺序去重并截断。
   *
   * <p>Provider 的响应形状不在本 Plugin 的契约控制内，因此这里只做保守的形状无关提取：任何 HTTPS 取值的字符串都可能是本次调用产出的媒体地址。
   * 只有媒体类能力会调用本方法，纯文本能力继续返回有界 JSON。
   */
  private static List<URI> mediaUris(JsonNode payload) {
    Set<String> collected = new LinkedHashSet<>();
    collectUrls(payload, collected);
    List<URI> uris = new ArrayList<>(collected.size());
    for (String candidate : collected) {
      try {
        uris.add(URI.create(candidate));
      } catch (IllegalArgumentException error) {
        // 收集阶段已经要求可解析的绝对 URL，这里只是防御性跳过。
      }
    }
    return uris.subList(0, Math.min(uris.size(), MAX_STAGED_MEDIA));
  }

  private static void collectUrls(JsonNode node, Set<String> collected) {
    if (node == null || collected.size() >= MAX_STAGED_MEDIA) {
      return;
    }
    if (node.isTextual()) {
      if (isAbsoluteHttpsUrl(node.textValue())) {
        collected.add(node.textValue());
      }
      return;
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        collectUrls(child, collected);
      }
    }
  }

  private static boolean isAbsoluteHttpsUrl(String value) {
    if (value == null || value.isBlank() || !value.startsWith(HTTPS_SCHEME)) {
      return false;
    }
    try {
      URI uri = new URI(value);
      return uri.isAbsolute()
          && HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null;
    } catch (URISyntaxException error) {
      return false;
    }
  }

  private static PluginMediaFamily mediaFamily(MavisCapability capability) {
    return switch (capability) {
      case TTS, TTS_BATCH, GENERATE_MUSIC -> PluginMediaFamily.AUDIO;
      case GENERATE_IMAGE, IMAGE_SEARCH, REVERSE_IMAGE -> PluginMediaFamily.IMAGE;
      case QUERY_VIDEO -> PluginMediaFamily.VIDEO;
      default -> PluginMediaFamily.DOCUMENT;
    };
  }

  private static boolean containsSessionResource(JsonNode node) {
    if (node == null) {
      return false;
    }
    if (node.isTextual()) {
      return node.textValue().startsWith(SESSION_RESOURCE_SCHEME);
    }
    if (node.isContainerNode()) {
      for (JsonNode child : node) {
        if (containsSessionResource(child)) {
          return true;
        }
      }
    }
    return false;
  }

  private JsonNode resolve(JsonNode node) {
    if (node.isTextual()) {
      String value = node.textValue();
      if (!value.startsWith(SESSION_RESOURCE_SCHEME)) {
        return node;
      }
      return TextNode.valueOf(resolveSessionResource(value));
    }
    if (node.isArray()) {
      ArrayNode resolved = ((ArrayNode) node).arrayNode(node.size());
      for (JsonNode child : node) {
        resolved.add(resolve(child));
      }
      return resolved;
    }
    if (node.isObject()) {
      ObjectNode resolved = ((ObjectNode) node).objectNode();
      node.fields()
          .forEachRemaining(entry -> resolved.set(entry.getKey(), resolve(entry.getValue())));
      return resolved;
    }
    return node;
  }

  private String resolveSessionResource(String resourceUri) {
    if (!SESSION_RESOURCE.matcher(resourceUri).matches()) {
      throw new PluginResourceUnavailableException(
          "unsupported session resource reference: " + describe(resourceUri));
    }
    URI resolved = gateway.resolveSessionResource(resourceUri);
    requireUsableDownloadUri(resolved);
    return resolved.toASCIIString();
  }

  /** 校验网关交回的地址确实是受控的 HTTPS 下载地址：无 userinfo、无 fragment、host 非空。 */
  private static void requireUsableDownloadUri(URI resolved) {
    if (resolved == null
        || !resolved.isAbsolute()
        || !HTTPS_SCHEME.equalsIgnoreCase(resolved.getScheme())
        || resolved.getHost() == null
        || resolved.getUserInfo() != null
        || resolved.getFragment() != null) {
      throw new PluginResourceUnavailableException(
          "the plugin resource gateway did not return a usable https download url");
    }
  }

  private void requireGateway(String reason) {
    if (gateway == null) {
      throw new PluginResourceUnavailableException(
          "no plugin resource gateway is bound in this deployment; "
              + reason
              + " cannot be served");
    }
  }

  /** 只保留 scheme 与形态，绝不回显可能含一次性签名的完整 URI。 */
  private static String describe(String resourceUri) {
    String lower = resourceUri.toLowerCase(Locale.ROOT);
    if (lower.startsWith(SESSION_RESOURCE_SCHEME)) {
      return SESSION_RESOURCE_SCHEME + "/resources/<blobId>";
    }
    Optional<String> scheme =
        Optional.ofNullable(resourceUri.split(":", 2))
            .filter(parts -> parts.length > 1)
            .map(parts -> parts[0]);
    return scheme.isPresent() ? scheme.get() + ":..." : "<unknown>";
  }
}
