package dev.mahoraga.memory.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * The validated internal source-event envelope. Trusted context, the canonical
 * source hash, and recorded time are deliberately not envelope fields; they are
 * never producer-controlled.
 */
public record SourceEvent(
    @NotBlank String sourceEventId,
    @NotNull EventType eventType,
    @NotBlank String sourceStreamId,
    @Positive long sourceSequence,
    int schemaVersion,
    @NotNull Instant occurredAt,
    @NotNull @Valid SourcePayload payload) {}
