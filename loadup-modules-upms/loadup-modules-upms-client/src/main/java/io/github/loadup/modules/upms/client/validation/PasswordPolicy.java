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
package io.github.loadup.modules.upms.client.validation;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.upms.client.dto.PasswordPolicyDTO;
import io.github.loadup.modules.upms.client.error.UpmsResultCodeEnum;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public enum PasswordPolicy {
    NORMAL(8, List.of()),
    STRONG(
            12,
            List.of(CharacterClass.UPPERCASE, CharacterClass.LOWERCASE, CharacterClass.DIGIT, CharacterClass.SYMBOL));

    public static final int MAX_UTF8_BYTES = 72;
    public static final String MESSAGE = "Password requires at least 8 Unicode characters and at most 72 UTF-8 bytes";

    private final int minCharacters;
    private final List<CharacterClass> requiredCharacterClasses;

    PasswordPolicy(int minCharacters, List<CharacterClass> requiredCharacterClasses) {
        this.minCharacters = minCharacters;
        this.requiredCharacterClasses = requiredCharacterClasses;
    }

    public boolean isValid(String value) {
        return violation(value) == null;
    }

    public void requireValid(String value) {
        UpmsResultCodeEnum error = violation(value);
        if (error != null) {
            throw new CommonException(error);
        }
    }

    public PasswordPolicyDTO requirements() {
        return new PasswordPolicyDTO(
                name().toLowerCase(Locale.ROOT), minCharacters, MAX_UTF8_BYTES, requiredCharacterClasses);
    }

    private UpmsResultCodeEnum violation(String value) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return UpmsResultCodeEnum.INVALID_PASSWORD;
        }
        int characters = value.codePointCount(0, value.length());
        if (characters < NORMAL.minCharacters) {
            return UpmsResultCodeEnum.INVALID_PASSWORD;
        }
        if (characters < minCharacters
                || requiredCharacterClasses.stream()
                        .anyMatch(type -> !type.pattern.matcher(value).find())) {
            return UpmsResultCodeEnum.PASSWORD_STRENGTH_REQUIRED;
        }
        return null;
    }

    public enum CharacterClass {
        UPPERCASE("\\p{Lu}"),
        LOWERCASE("\\p{Ll}"),
        DIGIT("\\p{Nd}"),
        SYMBOL("[\\p{P}\\p{S}]");

        private final Pattern pattern;

        CharacterClass(String expression) {
            pattern = Pattern.compile(expression);
        }
    }
}
