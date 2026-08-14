package dev.starryeye.organization.core.tuple;

import dev.starryeye.organization.core.model.SyncSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class SnapshotIds {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    private SnapshotIds() {
    }

    public static String generate(Instant at, SyncSource source) {
        return FORMAT.format(at) + "-" + source.name();
    }
}
