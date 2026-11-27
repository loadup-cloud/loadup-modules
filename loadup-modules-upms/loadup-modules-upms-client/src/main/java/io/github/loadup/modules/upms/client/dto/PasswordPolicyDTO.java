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
package io.github.loadup.modules.upms.client.dto;

import io.github.loadup.commons.json.ToStringAsJson;
import io.github.loadup.modules.upms.client.validation.PasswordPolicy.CharacterClass;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Requirements for new UPMS passwords")
public record PasswordPolicyDTO(
        @Schema(description = "Password policy name", example = "normal")
        String name,

        @Schema(description = "Minimum Unicode code points", example = "8")
        int minCharacters,

        @Schema(description = "Maximum UTF-8 bytes", example = "72")
        int maxUtf8Bytes,

        @Schema(description = "Required Unicode character classes")
        List<CharacterClass> requiredCharacterClasses) {
    @Override
    public String toString() {
        return ToStringAsJson.reflectionToString(this);
    }
}
