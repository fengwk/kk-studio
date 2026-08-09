package fun.fengwk.kkstudio.core.studio.resource;

import java.util.Map;

/** Canvas Resource 的浏览器直读信息，不暴露 bucket 或对象 key。 */
public record CanvasPresignedUrl(
    String method, String url, Map<String, String> headers, String expiresAt) {}
