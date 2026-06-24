package com.wpanther.eidasremotesigning.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.reflect.Field;
import java.util.Collection;

public class AtLeastOneOfValidator implements ConstraintValidator<AtLeastOneOf, Object> {

    private String[] fields;
    private String message;

    @Override
    public void initialize(AtLeastOneOf constraintAnnotation) {
        this.fields = constraintAnnotation.fields();
        // Build the message directly using String.format; do NOT rely on BV interpolation
        // of {fields} since that only works for standard constraint attributes.
        this.message = String.format("At least one of (%s) must be provided",
                String.join(", ", fields));
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (String fieldName : fields) {
            try {
                Field field = findField(value.getClass(), fieldName);
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                if (fieldValue != null) {
                    if (fieldValue instanceof Collection<?> col && !col.isEmpty()) {
                        return true;
                    } else if (!(fieldValue instanceof Collection<?>)) {
                        return true;
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
            }
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(fields[0])
                .addConstraintViolation();
        return false;
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
