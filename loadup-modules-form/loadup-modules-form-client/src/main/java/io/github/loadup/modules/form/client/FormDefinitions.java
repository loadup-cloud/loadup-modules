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

import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public final class FormDefinitions {
    public static final String ROOT = "ROOT";

    private FormDefinitions() {}

    public enum ValueType {
        TEXT,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        DATE,
        ENUM
    }

    public enum Widget {
        TEXT,
        TEXTAREA,
        NUMBER,
        CHECKBOX,
        DATE,
        SELECT,
        MULTI_SELECT
    }

    public enum Status {
        DRAFT,
        PUBLISHED,
        RETIRED
    }

    public enum Kind {
        GROUP,
        TEMPLATE
    }

    public enum Origin {
        GROUP_DEFAULT,
        TEMPLATE_DEFAULT,
        GROUP_LOCKED,
        TEMPLATE_LOCKED,
        USER
    }

    public record Field(
            String key,
            ValueType type,
            boolean multiple,
            String valueSetCode,
            String unit,
            String label,
            String description,
            List<Option> options,
            Constraints constraints) {}

    public record Option(String code, String label) {}

    public record Constraints(String min, String max, Integer maxLength) {}

    public record FieldVersion(String id, String namespace, int version, boolean systemOwned, Field field) {}

    public record Placement(
            String fieldVersionId,
            String section,
            Widget widget,
            boolean required,
            boolean locked,
            boolean templateOverrideAllowed,
            JsonNode initialValue) {}

    public record Group(List<Placement> fields) {}

    public record Rule(boolean required, boolean locked, Set<String> allowedOptions, JsonNode initialValue) {}

    public record Item(
            String itemKey,
            String groupVersionId,
            boolean required,
            boolean defaultSelected,
            Map<String, Rule> rules) {}

    public record Template(List<Item> items) {}

    public record Save<T>(String code, int version, Long expectedRowVersion, T definition) {}

    public record Version<T>(String id, String code, int version, long rowVersion, Status status, T definition) {}

    public record FieldSchema(
            Field field,
            String fieldVersionId,
            String section,
            Widget widget,
            boolean required,
            boolean locked,
            JsonNode initialValue,
            Origin initialOrigin) {}

    public record ItemSchema(String itemKey, boolean required, boolean defaultSelected, List<FieldSchema> fields) {}

    public record Release(String id, String templateVersionId, String code, int version, List<ItemSchema> items) {}
}
