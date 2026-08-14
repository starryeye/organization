package dev.starryeye.organization.core.model;

import java.util.Objects;

public record MemberRef(MemberType type, String id) {

    public MemberRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    public static MemberRef user(String id) {
        return new MemberRef(MemberType.USER, id);
    }

    public static MemberRef group(String id) {
        return new MemberRef(MemberType.GROUP, id);
    }
}
