package hu.patriksgit.paxapi.config;

import java.io.IOException;

/** Thrown when a required config key is missing or the config file is malformed. */
public final class ConfigException extends IOException {
    public ConfigException(String message) {
        super(message);
    }
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
