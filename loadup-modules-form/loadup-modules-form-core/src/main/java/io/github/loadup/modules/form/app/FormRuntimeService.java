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
package io.github.loadup.modules.form.app;

import static io.github.loadup.modules.form.client.FormException.Code.AMBIGUOUS_FIELD;
import static io.github.loadup.modules.form.client.FormException.Code.CONFLICT;
import static io.github.loadup.modules.form.client.FormException.Code.INVALID_SELECTION;
import static io.github.loadup.modules.form.client.FormException.Code.INVALID_STATE;
import static io.github.loadup.modules.form.client.FormException.Code.INVALID_VALUE;
import static io.github.loadup.modules.form.client.FormException.Code.LOCKED_FIELD;
import static io.github.loadup.modules.form.client.FormException.Code.NOT_FOUND;
import static io.github.loadup.modules.form.client.FormException.Code.REQUIRED_FIELD;
import static io.github.loadup.modules.form.client.FormException.Code.UNKNOWN_FIELD;
import static io.github.loadup.modules.form.client.FormInstances.Detail;
import static io.github.loadup.modules.form.client.FormInstances.SaveDraft;
import static io.github.loadup.modules.form.client.FormInstances.Start;
import static io.github.loadup.modules.form.client.FormInstances.Status;
import static io.github.loadup.modules.form.client.FormInstances.Submit;
import static io.github.loadup.modules.form.client.FormInstances.Value;
import static io.github.loadup.modules.form.domain.FormRules.absent;
import static io.github.loadup.modules.form.domain.FormRules.checkReadType;
import static io.github.loadup.modules.form.domain.FormRules.code;
import static io.github.loadup.modules.form.domain.FormRules.readScalar;
import static io.github.loadup.modules.form.domain.FormRules.reference;
import static io.github.loadup.modules.form.domain.FormRules.require;
import static io.github.loadup.modules.form.domain.FormRules.value;

import io.github.loadup.modules.form.client.FormDefinitions.FieldSchema;
import io.github.loadup.modules.form.client.FormDefinitions.ItemSchema;
import io.github.loadup.modules.form.client.FormDefinitions.Origin;
import io.github.loadup.modules.form.client.FormDefinitions.Release;
import io.github.loadup.modules.form.client.FormException;
import io.github.loadup.modules.form.client.FormRuntime;
import io.github.loadup.modules.form.infrastructure.FormStore;
import io.github.loadup.modules.form.infrastructure.FormStore.Instance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional
public class FormRuntimeService implements FormRuntime {
    private final FormStore store;

    public FormRuntimeService(FormStore store) {
        this.store = store;
    }

    private static ItemSchema item(Detail detail, String itemKey, String key) {
        var candidates = detail.release().items().stream()
                .filter(item -> itemKey == null
                        ? item.fields().stream()
                                .anyMatch(field -> field.field().key().equals(key))
                        : item.itemKey().equals(itemKey))
                .toList();
        if (candidates.size() > 1) {
            throw new FormException(AMBIGUOUS_FIELD, null, key, "Specify the item key");
        }
        if (candidates.isEmpty()) {
            throw new FormException(
                    itemKey == null ? UNKNOWN_FIELD : INVALID_SELECTION, itemKey, key, "Address not found");
        }
        ItemSchema item = candidates.getFirst();
        if (!detail.selectedItems().contains(item.itemKey())) {
            throw new FormException(INVALID_SELECTION, item.itemKey(), key, "Item is not selected");
        }
        return item;
    }

    private static FieldSchema field(ItemSchema item, String key) {
        return item.fields().stream()
                .filter(field -> field.field().key().equals(key))
                .findFirst()
                .orElseThrow(
                        () -> new FormException(UNKNOWN_FIELD, item.itemKey(), key, "Field is not in the release"));
    }

    private static void checkDraft(Instance instance, long expected) {
        if (instance.submitKey() != null) {
            throw new FormException(INVALID_STATE, "Instance is already submitted");
        }
        if (instance.rowVersion() != expected) {
            throw new FormException(CONFLICT, "Draft version has changed");
        }
    }

    @Override
    public Detail start(String namespace, String actor, Start command) {
        code(namespace);
        reference(actor, 256);
        require(command != null, "A start request is required");
        reference(command.releaseId(), 36);
        reference(command.requestKey(), 128);
        var existing = store.byStartKey(namespace, actor, command.requestKey());
        if (existing.isPresent()) {
            return replayStart(namespace, command, existing.get());
        }

        // Hold a shared template lock until the draft is created. Retirement takes an exclusive lock.
        Release release = store.release(namespace, command.releaseId(), true)
                .orElseThrow(() -> new FormException(NOT_FOUND, "Published release not found"));
        String id = UUID.randomUUID().toString();
        if (!store.insertInstance(namespace, actor, id, release.id(), command.requestKey())) {
            return replayStart(
                    namespace,
                    command,
                    store.byStartKey(namespace, actor, command.requestKey()).orElseThrow());
        }
        Set<String> selected = new HashSet<>();
        for (ItemSchema item : release.items()) {
            if (item.defaultSelected()) {
                selected.add(item.itemKey());
            }
        }
        store.replaceSelection(id, selected);
        return view(new Instance(id, release.id(), 1, null), release);
    }

    private Detail replayStart(String namespace, Start command, Instance existing) {
        if (!existing.releaseId().equals(command.releaseId())) {
            throw new FormException(CONFLICT, "The creation key is already bound to another release");
        }
        return view(existing, release(namespace, existing));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String namespace, String actor, String instanceId) {
        Instance instance = instance(namespace, actor, instanceId, false);
        return view(instance, release(namespace, instance));
    }

    @Override
    public Detail saveDraft(String namespace, String actor, SaveDraft command) {
        require(command != null, "A draft request is required");
        Instance instance = instance(namespace, actor, command.instanceId(), true);
        checkDraft(instance, command.expectedRowVersion());
        Release release = release(namespace, instance);
        Set<String> selected = command.selectedItems();
        Map<String, Map<String, JsonNode>> values = command.values();
        if (selected == null
                || selected.size() > release.items().size()
                || values == null
                || values.keySet().stream().anyMatch(key -> key == null || !selected.contains(key))) {
            throw new FormException(INVALID_SELECTION, "Only selected items may contain values");
        }
        verifySelection(release, selected, false);
        store.replaceSelection(instance.id(), selected);
        for (ItemSchema item : release.items()) {
            if (!values.containsKey(item.itemKey())) {
                continue;
            }
            var inputs = values.get(item.itemKey());
            if (inputs == null) {
                throw new FormException(INVALID_VALUE, item.itemKey(), null, "Expected a field value map");
            }
            if (inputs.size() > item.fields().size()) {
                throw new FormException(INVALID_VALUE, item.itemKey(), null, "Too many field values");
            }
            for (var input : inputs.entrySet()) {
                FieldSchema field = field(item, input.getKey());
                if (field.locked()) {
                    throw new FormException(LOCKED_FIELD, item.itemKey(), input.getKey(), "Field is locked");
                }
                value(item.itemKey(), field.field(), input.getValue());
                store.insertValue(instance.id(), item.itemKey(), field.fieldVersionId(), input.getValue(), Origin.USER);
            }
        }
        store.advanceInstance(instance.id(), null);
        return view(new Instance(instance.id(), instance.releaseId(), instance.rowVersion() + 1, null), release);
    }

    @Override
    public Detail submit(String namespace, String actor, Submit command) {
        require(command != null, "A submit request is required");
        reference(command.requestKey(), 128);
        Instance instance = instance(namespace, actor, command.instanceId(), true);
        Release release = release(namespace, instance);
        if (instance.submitKey() != null && instance.submitKey().equals(command.requestKey())) {
            if (command.expectedRowVersion() != instance.rowVersion() - 1) {
                throw new FormException(CONFLICT, "The submitted draft version differs");
            }
            return view(instance, release);
        }
        checkDraft(instance, command.expectedRowVersion());
        Set<String> selected = store.selectedItems(instance.id());
        verifySelection(release, selected, true);
        var resolved = defaults(release, selected, store.values(instance.id()));
        store.replaceSelection(instance.id(), selected);
        for (ItemSchema item : release.items()) {
            if (!selected.contains(item.itemKey())) {
                continue;
            }
            Map<String, Value> values = resolved.get(item.itemKey());
            for (FieldSchema field : item.fields()) {
                Value entry = values.get(field.field().key());
                if (entry == null) {
                    if (field.required()) {
                        throw new FormException(
                                REQUIRED_FIELD, item.itemKey(), field.field().key(), "Field is required");
                    }
                    continue;
                }
                value(item.itemKey(), field.field(), entry.value());
                store.insertValue(instance.id(), item.itemKey(), field.fieldVersionId(), entry.value(), entry.origin());
            }
        }
        store.advanceInstance(instance.id(), command.requestKey());
        return view(
                new Instance(instance.id(), instance.releaseId(), instance.rowVersion() + 1, command.requestKey()),
                release);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Map<String, Value>> readValues(String namespace, String actor, String instanceId) {
        return submitted(namespace, actor, instanceId).values();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> Optional<T> readValue(
            String namespace, String actor, String instanceId, String itemKey, String key, Class<T> type) {
        Detail detail = submitted(namespace, actor, instanceId);
        ItemSchema item = item(detail, itemKey, key);
        FieldSchema field = field(item, key);
        checkReadType(item.itemKey(), field.field(), type, false);
        return Optional.ofNullable(
                        detail.values().getOrDefault(item.itemKey(), Map.of()).get(key))
                .map(value -> readScalar(item.itemKey(), field.field(), value.value(), type));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> Optional<List<T>> readList(
            String namespace, String actor, String instanceId, String itemKey, String key, Class<T> elementType) {
        Detail detail = submitted(namespace, actor, instanceId);
        ItemSchema item = item(detail, itemKey, key);
        FieldSchema field = field(item, key);
        checkReadType(item.itemKey(), field.field(), elementType, true);
        return Optional.ofNullable(
                        detail.values().getOrDefault(item.itemKey(), Map.of()).get(key))
                .map(value -> {
                    List<T> result = new ArrayList<>();
                    for (JsonNode node : value.value()) {
                        result.add(readScalar(item.itemKey(), field.field(), node, elementType));
                    }
                    return List.copyOf(result);
                });
    }

    private Detail submitted(String namespace, String actor, String id) {
        Detail detail = detail(namespace, actor, id);
        if (detail.status() != Status.SUBMITTED) {
            throw new FormException(INVALID_STATE, "Only submitted values can be read");
        }
        return detail;
    }

    private void verifySelection(Release release, Set<String> selected, boolean submitting) {
        if ((submitting && selected.isEmpty())
                || release.items().stream()
                                .filter(item -> selected.contains(item.itemKey()))
                                .count()
                        != selected.size()
                || release.items().stream().anyMatch(item -> item.required() && !selected.contains(item.itemKey()))) {
            throw new FormException(INVALID_SELECTION, "Selection does not match the release");
        }
    }

    private Detail view(Instance instance, Release release) {
        var selected = store.selectedItems(instance.id());
        var stored = store.values(instance.id());
        boolean submitted = instance.submitKey() != null;
        return new Detail(
                instance.id(),
                release,
                submitted ? Status.SUBMITTED : Status.DRAFT,
                instance.rowVersion(),
                selected,
                submitted ? stored : defaults(release, selected, stored));
    }

    private Map<String, Map<String, Value>> defaults(
            Release release, Set<String> selected, Map<String, Map<String, Value>> stored) {
        Map<String, Map<String, Value>> resolved = new LinkedHashMap<>();
        for (ItemSchema item : release.items()) {
            if (!selected.contains(item.itemKey())) {
                continue;
            }
            Map<String, Value> values = new LinkedHashMap<>(stored.getOrDefault(item.itemKey(), Map.of()));
            for (FieldSchema field : item.fields()) {
                if (!absent(field.initialValue())) {
                    values.putIfAbsent(field.field().key(), new Value(field.initialValue(), field.initialOrigin()));
                }
            }
            resolved.put(item.itemKey(), Map.copyOf(values));
        }
        return Map.copyOf(resolved);
    }

    private Release release(String namespace, Instance instance) {
        return store.release(namespace, instance.releaseId(), false)
                .orElseThrow(() -> new IllegalStateException("Instance release is missing"));
    }

    private Instance instance(String namespace, String actor, String id, boolean lock) {
        return store.instance(code(namespace), reference(actor, 256), reference(id, 36), lock)
                .orElseThrow(() -> new FormException(NOT_FOUND, "Instance not found"));
    }
}
