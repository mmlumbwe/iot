package com.assettrack.iot.protocol;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking protocol handler implementations.
 * Used by ProtocolHandlerFactory to automatically discover and manage handlers.
 *
 * Example usage:
 *
 * @Protocol(value = "GT06", version = "1.2")
 * @Component
 * public class Gt06V12Handler implements ProtocolHandler {...}
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Protocol {
    /**
     * @return The protocol name (e.g., "GT06", "TELTONIKA")
     */
    String value();

    /**
     * @return The protocol version (e.g., "CODEC8", "1.2")
     * Defaults to "1.0" if not specified
     */
    String version() default "1.0";

    /**
     * @return Whether this is the default handler for the protocol
     * when no version is specified. Defaults to false.
     */
    boolean isDefault() default false;

    /**
     * @return The priority of this handler when multiple handlers
     * exist for the same protocol+version. Higher values take precedence.
     * Defaults to 0.
     */
    int priority() default 0;
}
