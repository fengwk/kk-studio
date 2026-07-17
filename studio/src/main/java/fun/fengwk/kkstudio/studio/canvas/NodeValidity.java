package fun.fengwk.kkstudio.studio.canvas;

/** Resource/input consistency for a Canvas node. Priority: BROKEN > STALE > EMPTY > CURRENT. */
public enum NodeValidity {
  CURRENT,
  EMPTY,
  STALE,
  BROKEN
}
