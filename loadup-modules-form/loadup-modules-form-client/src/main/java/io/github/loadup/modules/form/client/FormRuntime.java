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

import static io.github.loadup.modules.form.client.FormInstances.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface FormRuntime {
    Detail start(String namespace, String actor, Start command);

    Detail detail(String namespace, String actor, String instanceId);

    Detail saveDraft(String namespace, String actor, SaveDraft command);

    Detail submit(String namespace, String actor, Submit command);

    Map<String, Map<String, Value>> readValues(String namespace, String actor, String instanceId);

    <T> Optional<T> readValue(
            String namespace, String actor, String instanceId, String itemKey, String key, Class<T> type);

    <T> Optional<List<T>> readList(
            String namespace, String actor, String instanceId, String itemKey, String key, Class<T> elementType);
}
