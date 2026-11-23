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

import static io.github.loadup.modules.form.client.FormDefinitions.*;
import static io.github.loadup.modules.form.client.FormException.Code.*;
import static io.github.loadup.modules.form.domain.FormRules.*;

import io.github.loadup.modules.form.client.FormCatalog;
import io.github.loadup.modules.form.client.FormException;
import io.github.loadup.modules.form.domain.FormCompiler;
import io.github.loadup.modules.form.infrastructure.FormStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class FormCatalogService implements FormCatalog {
    private final FormStore store;
    private final FormCompiler compiler = new FormCompiler();

    public FormCatalogService(FormStore store) {
        this.store = store;
    }

    @Override
    public FieldVersion declareField(String namespace, int version, Field definition) {
        return register(namespace, version, definition, true);
    }

    @Override
    public FieldVersion publishField(String namespace, int version, Field definition) {
        return register(namespace, version, definition, false);
    }

    private FieldVersion register(String namespace, int version, Field definition, boolean systemOwned) {
        code(namespace);
        require(version > 0, "Version must be positive");
        Field field = field(definition);
        var identity = store.fieldIdentity(nextId(), namespace, field, systemOwned);
        if (identity.type() != field.type() || identity.multiple() != field.multiple()
                || identity.systemOwned() != systemOwned
                || !Objects.equals(identity.valueSetCode(), field.valueSetCode())
                || !Objects.equals(identity.unit(), field.unit())) {
            throw new FormException(CONFLICT, null, field.key(), "Field identity is already registered with another contract");
        }
        store.insertField(nextId(), identity.id(), version, field);
        FieldVersion recorded = store.fieldVersion(identity.id(), version);
        if (!recorded.field().equals(field)) {
            throw new FormException(CONFLICT, null, field.key(), "This field version already has another definition");
        }
        return recorded;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FieldVersion> fields(String namespace, String keyPrefix, int offset, int limit) {
        verifyPage(offset, limit);
        return store.fields(code(namespace), keyPrefix == null ? "" : reference(keyPrefix, 64), offset, limit);
    }

    @Override
    public Version<Group> saveGroup(String namespace, Save<Group> command) {
        verifySave(namespace, command);
        compiler.validateGroup(command.definition(), id -> fieldVersion(namespace, id));
        return save(namespace, Kind.GROUP, command, Group.class);
    }

    @Override
    public Version<Template> saveTemplate(String namespace, Save<Template> command) {
        verifySave(namespace, command);
        compile(namespace, null, command.code(), command.version(), command.definition(), null);
        return save(namespace, Kind.TEMPLATE, command, Template.class);
    }

    private void verifySave(String namespace, Save<?> command) {
        code(namespace);
        require(command != null && command.version() > 0, "A positive version is required");
        code(command.code());
    }

    private <T> Version<T> save(String namespace, Kind kind, Save<T> command, Class<T> type) {
        if (command.expectedRowVersion() == null) {
            var created = new Version<>(nextId(), command.code(), command.version(), 1, Status.DRAFT, command.definition());
            if (!store.insertDefinition(namespace, kind, created)) {
                throw new FormException(CONFLICT, "Definition version already exists");
            }
            return created;
        }
        var draft = store.definition(namespace, kind, command.code(), command.version(), type, true)
                .orElseThrow(() -> new FormException(NOT_FOUND, "Definition version not found"));
        expected(draft, command.expectedRowVersion(), Status.DRAFT);
        store.saveDefinition(draft.id(), command.definition());
        return version(namespace, kind, draft.id(), type, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Version<Group> group(String namespace, String id) {
        return version(namespace, Kind.GROUP, id, Group.class, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Version<Template> template(String namespace, String id) {
        return version(namespace, Kind.TEMPLATE, id, Template.class, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Version<Group>> findGroup(String namespace, String code, int version) {
        require(version > 0, "Version must be positive");
        return store.definition(code(namespace), Kind.GROUP, code(code), version, Group.class, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Version<Template>> findTemplate(String namespace, String code, int version) {
        require(version > 0, "Version must be positive");
        return store.definition(code(namespace), Kind.TEMPLATE, code(code), version, Template.class, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Version<Group>> groups(String namespace, int offset, int limit) {
        verifyPage(offset, limit);
        return store.definitions(code(namespace), Kind.GROUP, Group.class, offset, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Version<Template>> templates(String namespace, int offset, int limit) {
        verifyPage(offset, limit);
        return store.definitions(code(namespace), Kind.TEMPLATE, Template.class, offset, limit);
    }

    @Override
    public Version<Group> publishGroup(String namespace, String id, long expectedRowVersion) {
        var draft = version(namespace, Kind.GROUP, id, Group.class, true);
        if (draft.status() == Status.PUBLISHED && draft.rowVersion() - 1 == expectedRowVersion) return draft;
        expected(draft, expectedRowVersion, Status.DRAFT);
        compiler.validateGroup(draft.definition(), fieldId -> fieldVersion(namespace, fieldId));
        store.setStatus(id, Status.PUBLISHED);
        return group(namespace, id);
    }

    @Override
    @Transactional(readOnly = true)
    public Release preview(String namespace, String templateVersionId) {
        var template = template(namespace, templateVersionId);
        if (template.status() != Status.DRAFT) {
            return publishedRelease(templateVersionId);
        }
        return compile(namespace, template.id(), template.code(), template.version(), template.definition(), null);
    }

    @Override
    public Release publishTemplate(String namespace, String id, long expectedRowVersion) {
        var draft = version(namespace, Kind.TEMPLATE, id, Template.class, true);
        if (draft.status() == Status.PUBLISHED && draft.rowVersion() - 1 == expectedRowVersion) {
            return publishedRelease(id);
        }
        expected(draft, expectedRowVersion, Status.DRAFT);
        Release release = compile(namespace, id, draft.code(), draft.version(), draft.definition(), nextId());
        store.insertRelease(release);
        store.setStatus(id, Status.PUBLISHED);
        return release;
    }

    @Override
    public void retireTemplate(String namespace, String id, long expectedRowVersion) {
        var template = version(namespace, Kind.TEMPLATE, id, Template.class, true);
        expected(template, expectedRowVersion, Status.PUBLISHED);
        store.setStatus(id, Status.RETIRED);
    }

    private Release publishedRelease(String id) {
        return store.releaseForTemplate(id).orElseThrow(() -> new IllegalStateException("Published form release is missing"));
    }

    private Release compile(
            String namespace, String templateId, String code, int version, Template template, String releaseId) {
        var items = compiler.compile(template, groupId -> {
            var group = group(namespace, groupId);
            if (group.status() != Status.PUBLISHED) {
                throw new FormException(INVALID_STATE, "Templates reference published field group versions");
            }
            return group.definition();
        }, fieldId -> fieldVersion(namespace, fieldId));
        return new Release(releaseId, templateId, code, version, items);
    }

    private FieldVersion fieldVersion(String namespace, String id) {
        return store.field(namespace, id).orElseThrow(() -> new FormException(NOT_FOUND, "Field version not found"));
    }

    private <T> Version<T> version(String namespace, Kind kind, String id, Class<T> type, boolean lock) {
        return store.definition(code(namespace), kind, reference(id, 36), type, lock)
                .orElseThrow(() -> new FormException(NOT_FOUND, "Definition version not found"));
    }

    private static void expected(Version<?> version, long expected, Status status) {
        if (version.rowVersion() != expected) throw new FormException(CONFLICT, "Definition version has changed");
        if (version.status() != status) throw new FormException(INVALID_STATE, "Definition cannot be changed in its current state");
    }

    private static void verifyPage(int offset, int limit) {
        require(offset >= 0 && limit > 0 && limit <= 100, "Invalid pagination");
    }

    private static String nextId() {
        return UUID.randomUUID().toString();
    }
}
