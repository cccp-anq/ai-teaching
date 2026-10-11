package com.aijiaoxue.supervision.evaluation;

/** One recognized supervision-evaluation text line before domain validation. */
public record EvaluationTextRow(int rowIndex, String offeringId, String status, String completedAt) {}
