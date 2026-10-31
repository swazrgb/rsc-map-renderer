package openrsc.bot.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a packet type, factory or constructor that only works on a server with every one of
 * {@link #value()} switched on.
 *
 * <p>The check at send time is {@link Packet#requires()}; a packet type carrying this returns the
 * same extensions there.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.CONSTRUCTOR})
public @interface ExtendedOnly {

    Extension[] value();
}
