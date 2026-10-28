package fun.fengwk.kkstudio.ide.protocol;

/** IDE 控制命令的动作集合，与冻结契约 {@code daemon/src/contracts.ts} 的 {@code IdeAction} 一致。 */
public enum IdeAction {
  OPEN_VIEW,
  CHANGE_DIRECTORY,
  FOCUS_TERMINAL,
  CLOSE_VIEW,
  DETACH_VIEW,
  LIST_VIEWS
}
