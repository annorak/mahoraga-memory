package dev.mahoraga.memory.boundary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable nonempty set of finalized stream positions: the exact "as of"
 * knowledge a planner or report query may see. Positions are defensively
 * copied and stored sorted by stream id, so caller order can never affect
 * equality, canonical bytes, or the digest. The tenant is trusted query
 * context, deliberately not part of this reusable value.
 */
public record KnowledgeBoundary(List<BoundaryPosition> positions) {

  public KnowledgeBoundary {
    if (positions == null || positions.isEmpty()) {
      throw new IllegalArgumentException("a knowledge boundary requires at least one position");
    }
    List<BoundaryPosition> sorted = new ArrayList<>(positions.size());
    Set<String> seenStreams = new HashSet<>();
    for (BoundaryPosition position : positions) {
      if (position == null) {
        throw new IllegalArgumentException("a knowledge boundary rejects null positions");
      }
      if (!seenStreams.add(position.sourceStreamId())) {
        throw new IllegalArgumentException(
            "a knowledge boundary names stream %s exactly once"
                .formatted(position.sourceStreamId()));
      }
      sorted.add(position);
    }
    sorted.sort(Comparator.comparing(BoundaryPosition::sourceStreamId));
    positions = List.copyOf(sorted);
  }

  public static KnowledgeBoundary of(Collection<BoundaryPosition> positions) {
    return new KnowledgeBoundary(positions == null ? null : new ArrayList<>(positions));
  }
}
