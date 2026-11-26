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
package io.github.loadup.modules.gotone.client.error;

import io.github.loadup.commons.enums.ResultStatusEnum;
import io.github.loadup.commons.result.ResultCode;

public enum GotoneResultCode implements ResultCode {
    SERVICE_IDENTITY_REQUIRED("GOTONE_SERVICE_IDENTITY_REQUIRED", "Verified service identity is required"),
    INVALID_SERVICE_CODE("GOTONE_INVALID_SERVICE_CODE", "Invalid notification service code"),
    NO_CHANNEL_ROUTES("GOTONE_NO_CHANNEL_ROUTES", "No notification channels are enabled for this service code"),
    INVALID_CHANNEL_RECIPIENTS(
            "GOTONE_INVALID_CHANNEL_RECIPIENTS", "Provide valid recipients for each enabled channel"),
    INVALID_SUBMISSION("GOTONE_INVALID_SUBMISSION", "Invalid notification submission or configuration"),
    REQUEST_CONFLICT("GOTONE_REQUEST_CONFLICT", "Idempotency key or configuration version conflicts"),
    INVALID_TEMPLATE("GOTONE_INVALID_TEMPLATE", "Template fields or parameters are invalid"),
    GOTONE_NOT_FOUND("GOTONE_NOT_FOUND", "notification not found"),
    AUTHENTICATED_USER_IS_REQUIRED("GOTONE_AUTHENTICATED_USER_IS_REQUIRED", "authenticated user is required");
    private final String code;
    private final String message;

    GotoneResultCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getStatus() {
        return ResultStatusEnum.FAIL.getCode();
    }
}
