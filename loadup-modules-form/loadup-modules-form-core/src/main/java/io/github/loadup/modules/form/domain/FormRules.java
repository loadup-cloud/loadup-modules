/*
 * Copyright (C) 2025-2026 LoadUp Cloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.loadup.modules.form.domain;

import static io.github.loadup.modules.form.client.FormDefinitions.*;
import static io.github.loadup.modules.form.client.FormException.Code.*;

import io.github.loadup.modules.form.client.FormException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class FormRules {
    private static final String CODE = "[A-Z][A-Z0-9_]{0,63}";
    private static final String DECIMAL = "-?[0-9]{1,64}(\\.[0-9]{1,64})?";
    private static final BigInteger MAX_WIRE_INTEGER = BigInteger.valueOf(9_007_199_254_740_991L);

    private FormRules() {}

    public static String code(String value) {
        require(value != null && value.matches(CODE), "Expected an uppercase code");
        return value;
    }

    public static String reference(String value, int max) {
        require(value != null && !value.isBlank() && value.length() <= max, "Invalid identifier");
        return value;
    }

    public static void itemKey(String value) {
        require(value != null && value.length() <= 128, "Invalid item key");
        for (String part : value.split("\\.", -1)) {
            code(part);
        }
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new FormException(INVALID_DEFINITION, message);
        }
    }

    public static Field field(Field field) {
        require(field != null && field.type() != null, "A field type is required");
        code(field.key());
        reference(field.label(), 160);
        require(!field.multiple() || field.type() == ValueType.ENUM, "Only enums support multiple values");
        if (field.description() != null) {
            reference(field.description(), 1000);
        }
        if (field.unit() != null) {
            require(field.type() == ValueType.INTEGER || field.type() == ValueType.DECIMAL,
                    "Units require a numeric field");
            reference(field.unit(), 64);
        }
        List<Option> options = field.options() == null ? List.of() : field.options();
        require(options.size() <= 256, "Too many field options");
        Set<String> optionCodes = new HashSet<>();
        for (Option option : options) {
            require(option != null, "An option is required");
            require(optionCodes.add(code(option.code())), "Duplicate option code");
            reference(option.label(), 160);
        }
        if (field.type() == ValueType.ENUM) {
            require(!options.isEmpty(), "Enum fields must have options");
            reference(field.valueSetCode(), 255);
        } else {
            require(options.isEmpty() && field.valueSetCode() == null, "Only enums have a value set");
        }
        Constraints constraints = field.constraints();
        if (constraints != null) {
            if (constraints.maxLength() != null) {
                require(field.type() == ValueType.TEXT && constraints.maxLength() > 0
                        && constraints.maxLength() <= 4000, "Invalid text length limit");
            }
            if (constraints.min() != null || constraints.max() != null) {
                require(field.type() == ValueType.INTEGER || field.type() == ValueType.DECIMAL,
                        "Bounds require a numeric field");
                validateBounds(field.type(), constraints);
            }
        }
        return new Field(field.key(), field.type(), field.multiple(), field.valueSetCode(), field.unit(),
                field.label(), field.description(), List.copyOf(options), constraints);
    }

    private static void validateBounds(ValueType type, Constraints c) {
        try {
            require(c.min() == null || c.min().matches(DECIMAL), "Invalid minimum");
            require(c.max() == null || c.max().matches(DECIMAL), "Invalid maximum");
            BigDecimal min = c.min() == null ? null : new BigDecimal(c.min());
            BigDecimal max = c.max() == null ? null : new BigDecimal(c.max());
            if (type == ValueType.INTEGER) {
                require(min == null || min.toBigIntegerExact().abs().compareTo(MAX_WIRE_INTEGER) <= 0,
                        "Integer minimum exceeds the wire range");
                require(max == null || max.toBigIntegerExact().abs().compareTo(MAX_WIRE_INTEGER) <= 0,
                        "Integer maximum exceeds the wire range");
            }
            require(min == null || max == null || min.compareTo(max) <= 0, "Inverted numeric bounds");
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new FormException(INVALID_DEFINITION, "Invalid numeric bounds");
        }
    }

    public static void widget(Field field, Widget widget) {
        if (field.multiple()) {
            require(field.type() == ValueType.ENUM && widget == Widget.MULTI_SELECT,
                    "Multiple fields require an enum and a multi-select widget");
            return;
        }
        boolean valid = switch (field.type()) {
            case TEXT -> widget == Widget.TEXT || widget == Widget.TEXTAREA;
            case INTEGER, DECIMAL -> widget == Widget.NUMBER;
            case BOOLEAN -> widget == Widget.CHECKBOX;
            case DATE -> widget == Widget.DATE;
            case ENUM -> widget == Widget.SELECT;
        };
        require(valid, "Widget is incompatible with the field type");
    }

    public static boolean absent(JsonNode value) {
        return value == null || value.isNull();
    }

    public static void value(String itemKey, Field field, JsonNode value) {
        if (absent(value)) {
            throw new FormException(INVALID_VALUE, itemKey, field.key(), "Omit a field to clear its override");
        }
        boolean valid;
        if (field.multiple()) {
            Set<JsonNode> unique = new HashSet<>();
            valid = value.isArray() && !value.isEmpty() && value.size() <= 256;
            if (valid) {
                for (JsonNode entry : value) {
                    if (!scalar(field, entry) || !unique.add(entry)) {
                        valid = false;
                        break;
                    }
                }
            }
        } else {
            valid = scalar(field, value);
        }
        if (!valid) {
            throw new FormException(INVALID_VALUE, itemKey, field.key(), "Value violates the field definition");
        }
    }

    private static boolean scalar(Field field, JsonNode value) {
        try {
            return switch (field.type()) {
                case TEXT -> value.isTextual() && !value.asString().isBlank()
                        && value.asString().length() <= textLimit(field);
                case INTEGER -> value.isIntegralNumber()
                        && value.bigIntegerValue().abs().compareTo(MAX_WIRE_INTEGER) <= 0
                        && inRange(field, new BigDecimal(value.bigIntegerValue()));
                case DECIMAL -> value.isTextual() && value.asString().matches(DECIMAL)
                        && inRange(field, new BigDecimal(value.asString()));
                case BOOLEAN -> value.isBoolean();
                case DATE -> value.isTextual() && value.asString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                        && LocalDate.parse(value.asString()).getYear() > 0;
                case ENUM -> value.isTextual()
                        && field.options().stream().anyMatch(option -> option.code().equals(value.asString()));
            };
        } catch (DateTimeParseException | NumberFormatException ex) {
            return false;
        }
    }

    private static int textLimit(Field field) {
        Integer limit = field.constraints() == null ? null : field.constraints().maxLength();
        return limit == null ? 4000 : limit;
    }

    private static boolean inRange(Field field, BigDecimal value) {
        Constraints c = field.constraints();
        return value.precision() <= 128 && (c == null
                || (c.min() == null || value.compareTo(new BigDecimal(c.min())) >= 0)
                && (c.max() == null || value.compareTo(new BigDecimal(c.max())) <= 0));
    }

    public static void checkReadType(String item, Field field, Class<?> type, boolean multiple) {
        if (type == null) {
            throw new FormException(INVALID_TYPE, item, field.key(), "A Java value type is required");
        }
        boolean valid = switch (field.type()) {
            case TEXT -> type == String.class;
            case INTEGER -> type == BigInteger.class || type == Integer.class || type == Long.class;
            case DECIMAL -> type == BigDecimal.class;
            case BOOLEAN -> type == Boolean.class;
            case DATE -> type == LocalDate.class;
            case ENUM -> type == String.class || type.isEnum() && type.getName().equals(field.valueSetCode());
        };
        if (!valid || field.multiple() != multiple) {
            throw new FormException(INVALID_TYPE, item, field.key(), "Requested type does not match the published field");
        }
    }

    public static <T> T readScalar(String item, Field field, JsonNode value, Class<T> type) {
        try {
            Object converted = switch (field.type()) {
                case TEXT -> value.asString();
                case INTEGER -> {
                    BigInteger integer = value.bigIntegerValue();
                    if (type == Integer.class) yield Integer.valueOf(integer.intValueExact());
                    if (type == Long.class) yield Long.valueOf(integer.longValueExact());
                    yield integer;
                }
                case DECIMAL -> new BigDecimal(value.asString());
                case BOOLEAN -> value.booleanValue();
                case DATE -> LocalDate.parse(value.asString());
                case ENUM -> {
                    if (type == String.class) yield value.asString();
                    yield java.util.Arrays.stream(type.getEnumConstants())
                            .filter(option -> ((Enum<?>) option).name().equals(value.asString()))
                            .findFirst().orElseThrow();
                }
            };
            return type.cast(converted);
        } catch (ArithmeticException | java.util.NoSuchElementException ex) {
            throw new FormException(INVALID_TYPE, item, field.key(), "Value cannot be represented by the requested type");
        }
    }
}
