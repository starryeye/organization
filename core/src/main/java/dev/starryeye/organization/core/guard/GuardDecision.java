package dev.starryeye.organization.core.guard;

public record GuardDecision(boolean aborted, String message) {

    public static GuardDecision proceed() {
        return new GuardDecision(false, null);
    }

    public static GuardDecision abort(String message) {
        return new GuardDecision(true, message);
    }
}
