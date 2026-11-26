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

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(name = "FormField", description = "Field definition with its value type, options and constraints")
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

    @Schema(name = "FormOption", description = "Selectable value for an enumeration field")
    public record Option(String code, String label) {}

    @Schema(name = "FormConstraints", description = "Limits applied when validating a field value")
    public record Constraints(String min, String max, Integer maxLength) {}

    @Schema(name = "FormFieldVersion", description = "Published version of a field definition")
    public record FieldVersion(String id, String namespace, int version, boolean systemOwned, Field field) {}

    @Schema(name = "FormPlacement", description = "Placement and default value for a field within a group")
    public record Placement(
            String fieldVersionId,
            String section,
            Widget widget,
            boolean required,
            boolean locked,
            boolean templateOverrideAllowed,

            @Schema(description = "Default or locked JSON value matching the field type")
            JsonNode initialValue) {}

    @Schema(name = "FormGroup", description = "Ordered field placements")
    public record Group(List<Placement> fields) {}

    @Schema(name = "FormRule", description = "Template override for an item field")
    public record Rule(boolean required, boolean locked, Set<String> allowedOptions, JsonNode initialValue) {}

    @Schema(name = "FormItem", description = "Field group reference and overrides in a template version")
    public record Item(
            String itemKey,
            String groupVersionId,
            boolean required,
            boolean defaultSelected,

            @Schema(description = "Template overrides keyed by field key")
            Map<String, Rule> rules) {}

    @Schema(name = "FormTemplate", description = "Ordered items in a form template")
    public record Template(List<Item> items) {}

    @Schema(description = "New or updated group or template draft")
    public record Save<T>(
            String code,
            int version,

            @Schema(description = "Omit when creating a version. Supply the current rowVersion when updating.")
            Long expectedRowVersion,

            T definition) {}

    @Schema(description = "Stored version of a field group or template")
    public record Version<T>(
            String id,
            String code,
            int version,

            @Schema(description = "Current revision used for updates, publishing or retirement")
            long rowVersion,

            Status status,
            T definition) {}

    @Schema(name = "FormFieldSchema", description = "Resolved field and rendering settings")
    public record FieldSchema(
            Field field,
            String fieldVersionId,
            String section,
            Widget widget,
            boolean required,
            boolean locked,
            JsonNode initialValue,
            Origin initialOrigin) {}

    @Schema(name = "FormItemSchema", description = "Resolved item and its renderable fields")
    public record ItemSchema(String itemKey, boolean required, boolean defaultSelected, List<FieldSchema> fields) {}

    @Schema(name = "FormRelease", description = "Resolved template with its items and fields")
    public record Release(
            @Schema(description = "Release ID used to start a form instance")
            String id,

            String templateVersionId,
            String code,
            int version,
            List<ItemSchema> items) {}
}
