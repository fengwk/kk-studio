package fun.fengwk.kkstudio.ide.data;

/** 数据连接绑定的控制身份或 owner 租约已被替代；旧代际的连接与票据永久失效。 */
public final class IdeDataGenerationReplacedException extends IdeDataUnavailableException {

  public IdeDataGenerationReplacedException(String message) {
    super(message);
  }
}
