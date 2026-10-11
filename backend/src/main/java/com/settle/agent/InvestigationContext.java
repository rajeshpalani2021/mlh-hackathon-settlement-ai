package com.settle.agent;

import java.util.UUID;

/** Current investigation id for the thread running the agent, so tools can log their steps. */
public final class InvestigationContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private InvestigationContext() {}

    public static void set(UUID id) {
        CURRENT.set(id);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
