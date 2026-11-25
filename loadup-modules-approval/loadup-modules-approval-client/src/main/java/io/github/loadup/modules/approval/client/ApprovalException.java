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
package io.github.loadup.modules.approval.client;

import io.github.loadup.commons.result.ResultCode;

public final class ApprovalException extends RuntimeException {
    public enum Code implements ResultCode {
        APPROVAL_INVALID(422, "审批数据无效"),
        APPROVAL_FORBIDDEN(403, "没有此审批操作权限"),
        APPROVAL_NOT_FOUND(404, "审批记录不存在"),
        APPROVAL_CONFLICT(409, "审批状态冲突"),
        APPROVAL_STALE(412, "审批记录已更新");

        public final int httpStatus;
        private final String message;

        Code(int httpStatus, String message) {
            this.httpStatus = httpStatus;
            this.message = message;
        }

        public String getCode() { return name(); }
        public String getStatus() { return "F"; }
        public String getMessage() { return message; }
    }

    private final Code code;

    public ApprovalException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
