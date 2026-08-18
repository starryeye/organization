package dev.starryeye.organization.core.model;

import java.time.Instant;

public record SnapshotMeta(String id, Instant createdAt, SyncSource source, int tupleCount) {
}
