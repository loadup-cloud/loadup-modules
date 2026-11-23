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

import static io.github.loadup.modules.form.client.FormDefinitions.*;

import java.util.List;
import java.util.Optional;

public interface FormCatalog {
    FieldVersion declareField(String namespace, int version, Field field);

    FieldVersion publishField(String namespace, int version, Field field);

    List<FieldVersion> fields(String namespace, String keyPrefix, int offset, int limit);

    Version<Group> saveGroup(String namespace, Save<Group> command);

    Version<Group> group(String namespace, String id);

    Optional<Version<Group>> findGroup(String namespace, String code, int version);

    Version<Group> publishGroup(String namespace, String id, long expectedRowVersion);

    Version<Template> saveTemplate(String namespace, Save<Template> command);

    Version<Template> template(String namespace, String id);

    Optional<Version<Template>> findTemplate(String namespace, String code, int version);

    List<Version<Template>> templates(String namespace, int offset, int limit);

    List<Version<Group>> groups(String namespace, int offset, int limit);

    Release preview(String namespace, String templateVersionId);

    Release publishTemplate(String namespace, String id, long expectedRowVersion);

    void retireTemplate(String namespace, String id, long expectedRowVersion);
}
