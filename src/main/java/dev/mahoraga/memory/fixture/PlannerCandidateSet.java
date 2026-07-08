package dev.mahoraga.memory.fixture;

import dev.mahoraga.memory.contract.TrustedContext;
import dev.mahoraga.memory.fixture.RunnerManifest.RunnerCandidate;
import java.util.List;
import java.util.Objects;

/**
 * The fixture projection used to build planner inputs. It contains the trusted
 * tenant, action budget, and each opaque candidate's authoritative Deployment
 * target and verification key. It contains no scenario label, frozen outcome,
 * source-event reference, classification, or E2 fact. It is projected from a
 * {@link RunnerManifest}; planner inputs never receive the manifest itself.
 */
public record PlannerCandidateSet(
    String tenantId, int actionBudget, List<PlannerCandidate> candidates) {

  public PlannerCandidateSet {
    Objects.requireNonNull(tenantId, "tenantId");
    candidates = List.copyOf(candidates);
  }

  /** One opaque candidate with just the target and verification key the planner may use. */
  public record PlannerCandidate(
      String candidateId, DeploymentTarget target, String verificationKey) {}

  /**
   * Projects the runner manifest onto the planner-safe fields under the trusted
   * tenant. Reads only the target and verification key of each candidate, so a
   * label or outcome cannot cross this boundary even if the manifest gains one.
   */
  public static PlannerCandidateSet from(TrustedContext context, RunnerManifest manifest) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(manifest, "manifest");
    List<PlannerCandidate> projected =
        manifest.candidates().stream().map(PlannerCandidateSet::project).toList();
    return new PlannerCandidateSet(context.tenantId(), manifest.actionBudget(), projected);
  }

  private static PlannerCandidate project(RunnerCandidate candidate) {
    return new PlannerCandidate(
        candidate.candidateId(), candidate.target(), candidate.verificationKey());
  }
}
