package se.l4.exofind.engine.api.errors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Holds the {@link ReturnsError} annotations of one endpoint. Java requires the
 * container; nothing writes it by hand.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReturnsErrors {
	ReturnsError[] value();
}
