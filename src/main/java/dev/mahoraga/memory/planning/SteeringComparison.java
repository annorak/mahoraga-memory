package dev.mahoraga.memory.planning;

import java.util.List;
import java.util.Objects;

/**
 * The validated two-arm result: both executed orders and causative metrics
 * alongside the equality evidence proving the arms differed only in memory
 * features. Produced exclusively by {@link SteeringEvidenceComparator}; the
 * metric values come from the arms, never from an expected constant.
 */
public record SteeringComparison(
    List<String> controlExecutedOrder,
    List<String> memoryExecutedOrder,
    int controlActionsBeforeRegression,
    int memoryActionsBeforeRegression,
    String e1SemanticDigest,
    String candidateInputDigest,
    String e2FactSetDigest,
    String memoryReportDigest) {

  public SteeringComparison {
    controlExecutedOrder = List.copyOf(Objects.requireNonNull(controlExecutedOrder));
    memoryExecutedOrder = List.copyOf(Objects.requireNonNull(memoryExecutedOrder));
    requirePositive(controlActionsBeforeRegression, "controlActionsBeforeRegression");
    requirePositive(memoryActionsBeforeRegression, "memoryActionsBeforeRegression");
    requireNonblank(e1SemanticDigest, "e1SemanticDigest");
    requireNonblank(candidateInputDigest, "candidateInputDigest");
    requireNonblank(e2FactSetDigest, "e2FactSetDigest");
    requireNonblank(memoryReportDigest, "memoryReportDigest");
  }

  private static void requirePositive(int value, String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be a one-based executed position");
    }
  }

  private static void requireNonblank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("a steering comparison requires a nonblank " + field);
    }
  }
}
