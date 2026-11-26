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

import io.github.loadup.modules.form.client.FormDefinitions.Origin;
import io.github.loadup.modules.form.client.FormDefinitions.Release;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class FormInstances {
    private FormInstances() {}

    public enum Status {
        DRAFT,
        SUBMITTED
    }

    @Schema(name = "FormStart", description = "Request to start a form instance from a release")
    public record Start(
            String releaseId,

            @Schema(description = "Idempotency key for starting the form instance")
            String requestKey) {}

    @Schema(name = "FormSaveDraft", description = "Replacement selection and user-entered values for a draft")
    public record SaveDraft(
            String instanceId,

            @Schema(description = "Current rowVersion of the draft")
            long expectedRowVersion,

            @Schema(description = "Complete set of selected item keys")
            Set<String> selectedItems,

            @Schema(
                    description = "User overrides keyed by item and field. Values follow the release's field types.",
                    example = "{\"ROOT\":{\"DEVICE_TYPE\":\"LAPTOP\",\"QUANTITY\":2}}")
            Map<String, Map<String, JsonNode>> values) {}

    @Schema(name = "FormSubmit", description = "Request to submit a form draft")
    public record Submit(
            String instanceId,

            @Schema(description = "Current rowVersion of the draft")
            long expectedRowVersion,

            @Schema(description = "Idempotency key for submitting the draft")
            String requestKey) {}

    @Schema(name = "FormValue", description = "Resolved field value and its source")
    public record Value(JsonNode value, Origin origin) {}

    @Schema(name = "FormDetail", description = "Form instance, release and resolved values")
    public record Detail(
            String id,
            Release release,
            Status status,
            long rowVersion,
            Set<String> selectedItems,

            @Schema(description = "Resolved values keyed first by item key and then by field key")
            Map<String, Map<String, Value>> values) {}
}
