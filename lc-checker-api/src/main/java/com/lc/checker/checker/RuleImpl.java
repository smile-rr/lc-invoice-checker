package com.lc.checker.checker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a {@link SpiRuleChecker} bean as the Java implementation of a specific rule id.
 * Meta-annotated with {@code @Component} so Spring picks it up on component scan.
 *
 * <pre>{@code
 * @RuleImpl("INV-027")
 * public class PresentationPeriodChecker implements SpiRuleChecker { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface RuleImpl {

    /** The rule id this bean handles (e.g. {@code "INV-027"}). */
    String value();
}
