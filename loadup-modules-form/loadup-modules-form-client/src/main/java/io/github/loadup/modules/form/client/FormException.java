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
package io.github.loadup.modules.form.client;

import io.github.loadup.commons.enums.ResultStatusEnum;
import io.github.loadup.commons.result.ResultCode;

public final class FormException extends RuntimeException {
    private final Code code;
    private final String itemKey;
    private final String fieldKey;

    public FormException(Code code, String message) {
        this(code, null, null, message);
    }

    public FormException(Code code, String itemKey, String fieldKey, String message) {
        super(message);
        this.code = code;
        this.itemKey = itemKey;
        this.fieldKey = fieldKey;
    }

    public Code code() {
        return code;
    }

    public String itemKey() {
        return itemKey;
    }

    public String fieldKey() {
        return fieldKey;
    }

    public enum Code implements ResultCode {
        INVALID_DEFINITION("Invalid form definition"),
        INVALID_VALUE("Invalid field value"),
        UNKNOWN_FIELD("Unknown form field"),
        INVALID_TYPE("Invalid field type"),
        REQUIRED_FIELD("Required field is missing"),
        INVALID_SELECTION("Invalid form item selection"),
        AMBIGUOUS_FIELD("Field belongs to multiple items"),
        LOCKED_FIELD("Field is locked"),
        NOT_FOUND("Form resource not found"),
        CONFLICT("Form version conflict"),
        INVALID_STATE("Invalid form state");

        private final String message;

        Code(String message) {
            this.message = message;
        }

        @Override
        public String getCode() {
            return "FORM_" + name();
        }

        @Override
        public String getStatus() {
            return ResultStatusEnum.FAIL.getCode();
        }

        @Override
        public String getMessage() {
            return message;
        }
    }
}
