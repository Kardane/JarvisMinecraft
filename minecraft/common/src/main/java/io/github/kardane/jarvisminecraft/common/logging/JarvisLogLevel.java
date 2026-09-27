package io.github.kardane.jarvisminecraft.common.logging;

public enum JarvisLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR;

    public boolean allows(JarvisLogLevel eventLevel) {
        return eventLevel.ordinal() >= ordinal();
    }
}
