package fun.fengwk.kkstudio.core.studio.resource;

import java.util.Map;

/** Canvas upload reserve 的浏览器直传信息，不暴露 bucket 或对象 key。 */
public record CanvasUploadReservation(
    long uploadId, String method, String url, Map<String, String> headers, String expiresAt) {}
