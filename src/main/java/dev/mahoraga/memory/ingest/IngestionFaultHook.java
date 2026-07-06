package dev.mahoraga.memory.ingest;

import org.jdbi.v3.core.Handle;

/**
 * Test-only fault seam fired on the active ingestion handle after each durable
 * write stage of one ingestion transaction. Production binds {@link #NO_FAULTS},
 * which does nothing and has no configuration, environment, or server-mode
 * switch; atomicity tests inject one deterministic failure per stage to prove
 * the source event and every derived write commit or roll back as one unit.
 */
@FunctionalInterface
public interface IngestionFaultHook {

  /** The normal binding: zero work, zero side effects, on every stage. */
  IngestionFaultHook NO_FAULTS = (stage, handle) -> {};

  void afterStage(Stage stage, Handle handle);

  /**
   * The durable write stages of one ingestion transaction, in execution order.
   * A stage fires only when the current event actually performs that write.
   */
  enum Stage {
    AFTER_SOURCE_EVENT_INSERT,
    AFTER_CANONICAL_ASSET_RESOLUTION,
    AFTER_ASSET_OBSERVATION_WRITE,
    AFTER_FINDING_RESOLUTION,
    AFTER_FINDING_OCCURRENCE_WRITE,
    AFTER_TEST_ATTEMPT_WRITE,
    AFTER_ENGAGEMENT_FINALIZATION_WRITE,
    BEFORE_TRANSACTION_RETURN
  }
}
