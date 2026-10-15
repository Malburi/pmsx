package kr.pmsx.as.ingest;

import java.time.OffsetDateTime;
import java.util.Map;

public final class RunDtos {

    private RunDtos() {
    }

    public record Diff(int screensAdded, int screensRemoved, int elementsAdded, int elementsRemoved, int edgesAdded,
                       int edgesRemoved) {
    }

    public record RunSummary(String runId, String systemId, int seq, OffsetDateTime collectedAt,
                             OffsetDateTime uploadedAt, Map<String, Number> stats, Diff diff) {
    }
}
