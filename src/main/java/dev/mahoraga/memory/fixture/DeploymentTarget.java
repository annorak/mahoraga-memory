package dev.mahoraga.memory.fixture;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The authoritative natural key of one Kubernetes Deployment
 * ({@code cluster_id + resource_kind + resource_uid}). It carries no ephemeral
 * Pod signals, no scenario label, and no outcome, so both the runner manifest
 * and the planner-safe projection can name a candidate's target without leaking
 * anything the planner may not see.
 */
public record DeploymentTarget(
    @JsonProperty("cluster_id") String clusterId,
    @JsonProperty("resource_kind") String resourceKind,
    @JsonProperty("resource_uid") String resourceUid) {

  public DeploymentTarget {
    requireNonblank(clusterId, "cluster_id");
    requireNonblank(resourceKind, "resource_kind");
    requireNonblank(resourceUid, "resource_uid");
  }

  private static void requireNonblank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("deployment target " + field + " must be nonblank");
    }
  }
}
