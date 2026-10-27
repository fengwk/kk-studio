package fun.fengwk.kkstudio.ide.mux;

/** DATA 帧的载荷种类；HTTP DATA 不允许 FIN。 */
public enum MuxDataKind {
  HTTP,
  WS_TEXT,
  WS_BINARY
}
