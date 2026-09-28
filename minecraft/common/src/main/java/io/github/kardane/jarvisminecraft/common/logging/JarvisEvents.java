package io.github.kardane.jarvisminecraft.common.logging;

public final class JarvisEvents {
    public static final String RUNTIME_STARTED = "runtime.started";
    public static final String RUNTIME_STOPPED = "runtime.stopped";
    public static final String CONFIG_LOADED = "config.loaded";
    public static final String CONFIG_RELOAD_FAILED = "config.reload_failed";
    public static final String CONVERSATION_ARCHIVE_FAILED =
        "conversation_archive.failed";
    public static final String CONVERSATION_ARCHIVE_DROPPED =
        "conversation_archive.dropped";

    public static final String REQUEST_ACCEPTED = "request.accepted";
    public static final String REQUEST_COMPLETED = "request.completed";
    public static final String REQUEST_FAILED = "request.failed";
    public static final String REQUEST_CANCELLED = "request.cancelled";
    public static final String REQUEST_REPLY_SUPPRESSED = "request.reply_suppressed";

    public static final String JEV_COMPLETED = "jev.completed";
    public static final String JEV_FAILED = "jev.failed";
    public static final String JEV_FALLBACK = "jev.fallback";
    public static final String ROUTING_RESOLVED = "routing.resolved";
    public static final String REASONING_RESOLVED = "reasoning.resolved";

    public static final String LUNA_ROUND_STARTED = "luna.round_started";
    public static final String LUNA_ROUND_COMPLETED = "luna.round_completed";
    public static final String LUNA_FAILED = "luna.failed";

    public static final String TOOL_EXPOSURE_RESOLVED =
        "tool.exposure_resolved";
    public static final String TOOL_EXPOSED = "tool.exposed";
    public static final String TOOL_STARTED = "tool.started";
    public static final String TOOL_COMPLETED = "tool.completed";
    public static final String TOOL_DENIED = "tool.denied";
    public static final String TOOL_OUTCOME_UNKNOWN = "tool.outcome_unknown";

    public static final String SCHEDULE_CREATED = "schedule.created";
    public static final String SCHEDULE_RUN_STARTED = "schedule.run_started";
    public static final String SCHEDULE_RUN_COMPLETED = "schedule.run_completed";
    public static final String SCHEDULE_CANCELLED = "schedule.cancelled";
    public static final String SCHEDULE_ABORTED = "schedule.aborted";

    public static final String PROACTIVE_CANDIDATE = "proactive.candidate";
    public static final String PROACTIVE_ACCEPTED = "proactive.accepted";
    public static final String PROACTIVE_IGNORED = "proactive.ignored";
    public static final String PROACTIVE_FAILED = "proactive.failed";

    public static final String AUDIT_DEGRADED = "audit.degraded";
    public static final String AUDIT_UNHEALTHY = "audit.unhealthy";
    public static final String AUDIT_RECOVERED = "audit.recovered";

    public static final String SESSION_STARTED = "session.started";
    public static final String SESSION_ENDED = "session.ended";
    public static final String SESSION_EXPIRED = "session.expired";
    public static final String PLATFORM_OFF_THREAD_CALLBACK =
        "platform.off_thread_callback";

    private JarvisEvents() {
    }
}
