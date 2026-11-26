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
package io.github.loadup.modules.form.domain;

import static io.github.loadup.modules.form.client.FormDefinitions.Field;
import static io.github.loadup.modules.form.client.FormDefinitions.FieldSchema;
import static io.github.loadup.modules.form.client.FormDefinitions.FieldVersion;
import static io.github.loadup.modules.form.client.FormDefinitions.Group;
import static io.github.loadup.modules.form.client.FormDefinitions.Item;
import static io.github.loadup.modules.form.client.FormDefinitions.ItemSchema;
import static io.github.loadup.modules.form.client.FormDefinitions.Option;
import static io.github.loadup.modules.form.client.FormDefinitions.Origin;
import static io.github.loadup.modules.form.client.FormDefinitions.Placement;
import static io.github.loadup.modules.form.client.FormDefinitions.ROOT;
import static io.github.loadup.modules.form.client.FormDefinitions.Rule;
import static io.github.loadup.modules.form.client.FormDefinitions.Template;
import static io.github.loadup.modules.form.domain.FormRules.absent;
import static io.github.loadup.modules.form.domain.FormRules.itemKey;
import static io.github.loadup.modules.form.domain.FormRules.reference;
import static io.github.loadup.modules.form.domain.FormRules.require;
import static io.github.loadup.modules.form.domain.FormRules.value;
import static io.github.loadup.modules.form.domain.FormRules.widget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

public final class FormCompiler {
    public void validateGroup(Group group, Function<String, FieldVersion> fields) {
        groupFields(null, group, Map.of(), fields);
    }

    public List<ItemSchema> compile(
            Template template, Function<String, Group> groups, Function<String, FieldVersion> fields) {
        require(
                template != null
                        && template.items() != null
                        && !template.items().isEmpty()
                        && template.items().size() <= 32,
                "Invalid template item count");
        Set<String> keys = new HashSet<>();
        List<ItemSchema> result = new ArrayList<>();
        for (Item item : template.items()) {
            require(item != null, "A template item is required");
            itemKey(item.itemKey());
            require(keys.add(item.itemKey()), "Duplicate item key");
            require(!item.required() || item.defaultSelected(), "Required items must be selected by default");
            var rules = item.rules() == null ? Map.<String, Rule>of() : item.rules();
            Group group = groups.apply(reference(item.groupVersionId(), 64));
            result.add(new ItemSchema(
                    item.itemKey(),
                    item.required(),
                    item.defaultSelected(),
                    groupFields(item.itemKey(), group, rules, fields)));
        }
        require(!keys.contains(ROOT) || keys.size() == 1, "ROOT is reserved for single-item templates");
        return List.copyOf(result);
    }

    private List<FieldSchema> groupFields(
            String item, Group group, Map<String, Rule> rules, Function<String, FieldVersion> versions) {
        require(
                group != null
                        && group.fields() != null
                        && !group.fields().isEmpty()
                        && group.fields().size() <= 128,
                "Invalid group field count");
        Set<String> keys = new HashSet<>();
        List<FieldSchema> result = new ArrayList<>();
        for (Placement placement : group.fields()) {
            require(placement != null, "A field placement is required");
            var version = versions.apply(reference(placement.fieldVersionId(), 64));
            Field field = version.field();
            require(keys.add(field.key()), "Duplicate field key in group");
            widget(field, placement.widget());
            if (placement.section() != null) {
                reference(placement.section(), 160);
            }
            Rule rule = rules.get(field.key());
            if (rule != null) {
                require(placement.templateOverrideAllowed() && !placement.locked(), "Field override is forbidden");
            }
            result.add(resolve(item, placement, field, rule));
        }
        require(
                keys.containsAll(rules.keySet()) && rules.values().stream().noneMatch(Objects::isNull),
                "Unknown or empty field override");
        return List.copyOf(result);
    }

    private FieldSchema resolve(String item, Placement placement, Field field, Rule rule) {
        boolean required = placement.required();
        boolean locked = placement.locked();
        JsonNode initial = placement.initialValue();
        Origin origin = locked ? Origin.GROUP_LOCKED : Origin.GROUP_DEFAULT;
        if (rule != null) {
            required |= rule.required();
            locked = rule.locked();
            if (rule.allowedOptions() != null) {
                Set<String> allowed = rule.allowedOptions();
                Set<String> available =
                        field.options().stream().map(Option::code).collect(Collectors.toSet());
                require(!allowed.isEmpty() && available.containsAll(allowed), "Invalid allowed option subset");
                field = new Field(
                        field.key(),
                        field.type(),
                        field.multiple(),
                        field.valueSetCode(),
                        field.unit(),
                        field.label(),
                        field.description(),
                        field.options().stream()
                                .filter(option -> allowed.contains(option.code()))
                                .toList(),
                        field.constraints());
            }
            if (!absent(rule.initialValue())) {
                initial = rule.initialValue();
                origin = locked ? Origin.TEMPLATE_LOCKED : Origin.TEMPLATE_DEFAULT;
            } else if (locked) {
                origin = Origin.TEMPLATE_LOCKED;
            }
        }
        require(!locked || !absent(initial), "A locked field requires a value");
        if (!absent(initial)) {
            value(item, field, initial);
        }
        return new FieldSchema(
                field,
                placement.fieldVersionId(),
                placement.section(),
                placement.widget(),
                required,
                locked,
                absent(initial) ? null : initial,
                absent(initial) ? null : origin);
    }
}
