package dev.starryeye.organization.core.model;

import java.util.Map;

public record DirectorySnapshot(
        Map<String, DirectoryUser> users,
        Map<String, DirectoryGroup> groups
) {

    public DirectorySnapshot {
        users = users == null ? Map.of() : Map.copyOf(users);
        groups = groups == null ? Map.of() : Map.copyOf(groups);
    }

    public static DirectorySnapshot empty() {
        return new DirectorySnapshot(Map.of(), Map.of());
    }
}
