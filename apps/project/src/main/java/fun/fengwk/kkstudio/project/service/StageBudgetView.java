package fun.fengwk.kkstudio.project.service;

/** 阶段额度视图：从 Run 历史实时计算的已消耗与剩余次数。 */
public record StageBudgetView(
    String state, int maxRuns, long budgetAfterOrdinal, long usedRuns, long remainingRuns) {}
