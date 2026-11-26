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
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class FormInstances {
    private FormInstances() {}

    public enum Status {
        DRAFT,
        SUBMITTED
    }

    public record Start(String releaseId, String requestKey) {}

    public record SaveDraft(
            String instanceId,
            long expectedRowVersion,
            Set<String> selectedItems,
            Map<String, Map<String, JsonNode>> values) {}

    public record Submit(String instanceId, long expectedRowVersion, String requestKey) {}

    public record Value(JsonNode value, Origin origin) {}

    public record Detail(
            String id,
            Release release,
            Status status,
            long rowVersion,
            Set<String> selectedItems,
            Map<String, Map<String, Value>> values) {}
}
