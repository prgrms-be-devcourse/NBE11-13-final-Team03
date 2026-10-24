package com.team3.backend.tracking.domain.model;

/** JSON Pointer path. Presence flags distinguish a missing property from an explicit JSON null. */
public record SnapshotDifference(String path, boolean beforePresent, Object before,
                                 boolean afterPresent, Object after) {
}
