package fun.fengwk.kkstudio.platform.daemon;

/** Signed GET details needed by a Daemon to download one registered archive. */
public record DaemonPresignedGet(String method, String url, long expiresAtEpochMs) {

  public DaemonPresignedGet {
    if (!"GET".equals(method)) {
      throw new IllegalArgumentException("method must be GET");
    }
    if (url == null || url.isBlank() || url.length() > DaemonPresignedPut.MAX_URL_CHARS) {
      throw new IllegalArgumentException("url must be non-blank and within the URL limit");
    }
    if (expiresAtEpochMs < 0) {
      throw new IllegalArgumentException("expiresAtEpochMs must not be negative");
    }
  }
}
