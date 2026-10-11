package com.aijiaoxue.supervision.evaluation;

import java.sql.Timestamp;

/** One fully validated evaluation row handed to the transactional writer. */
public record NormalizedEvaluation(int rowIndex, long offeringId, String status, Timestamp completedAt) {}
