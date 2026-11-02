package com.example.orderworkflow.inventory.api;

import java.lang.annotation.*;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotNull
@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")
@ReportAsSingleViolation
public @interface Identifier {
    String message() default "must be an identifier of 1 to 128 ASCII letters, digits, underscores or hyphens, starting with a letter or digit";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
