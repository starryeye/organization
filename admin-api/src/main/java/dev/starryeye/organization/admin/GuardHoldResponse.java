package dev.starryeye.organization.admin;

import dev.starryeye.organization.core.model.GuardHold;

import java.util.List;

/**
 * 가드가 멈춘 회차의 지우려던 것의 내역 응답(설계 2026-11-20 §3.1 응답). 저장의 {@code ids} 가 여기서는 {@code sample} 이다 —
 * 갈래마다 수와 사전순 앞 10개 예다.
 */
public record GuardHoldResponse(Lines lines, Users users, Groups groups) {

    public record Lines(int total, int directMember, int child, int other) {
    }

    public record Users(Sample gone, Sample inactive, Sample losingMembership) {
    }

    public record Groups(Sample gone, Sample losingParent) {
    }

    public record Sample(int count, List<String> sample) {

        static Sample from(GuardHold.Sample source) {
            return new Sample(source.count(), source.ids());
        }
    }

    public static GuardHoldResponse from(GuardHold hold) {
        return new GuardHoldResponse(
                new Lines(hold.totalLines(), hold.directMemberLines(), hold.childLines(), hold.otherLines()),
                new Users(Sample.from(hold.usersGone()), Sample.from(hold.usersInactive()),
                        Sample.from(hold.usersLosingMembership())),
                new Groups(Sample.from(hold.groupsGone()), Sample.from(hold.groupsLosingParent())));
    }
}
