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
package io.github.loadup.modules.approval.app;

import static io.github.loadup.modules.approval.app.ApprovalRules.*;
import static io.github.loadup.modules.approval.client.Approval.*;
import static io.github.loadup.modules.approval.client.ApprovalException.Code.*;

import io.github.loadup.modules.approval.infrastructure.ApprovalStore;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

final class ApprovalDefinitions {
    private final ApprovalStore store;
    private final ApprovalRules rules;
    private final TransactionTemplate tx;

    ApprovalDefinitions(ApprovalStore store, ApprovalRules rules, TransactionTemplate tx) {
        this.store = store;
        this.rules = rules;
        this.tx = tx;
    }

    List<Definition> list(Caller caller, String app, int offset, int limit) {
        permit(caller, app, "design");
        page(offset, limit);
        return store.definitions(app, offset, limit);
    }

    Definition get(String app, String id) {
        return store.definition(app, id).orElseThrow(() -> error(APPROVAL_NOT_FOUND, "流程版本不存在"));
    }

    Definition active(String app, String flow) {
        return store.active(app, flow).orElseThrow(() -> error(APPROVAL_NOT_FOUND, "流程没有启用版本"));
    }

    Definition create(Caller caller, String app, String flow, Spec spec, String source) {
        permit(caller, app, "design");
        human(caller);
        key(app);
        key(flow);
        return tx.execute(status -> {
            store.lockFlow(app, flow);
            Spec input = spec;
            if (source != null) {
                Definition original = get(app, source);
                require(original.flowKey().equals(flow) && original.status() != Status.DRAFT, "请选择已发布的来源版本");
                input = original.spec();
            }
            rules.validateSpec(app, input, false);
            String id = ApprovalStore.id();
            int changed = store.sql("""
                    INSERT INTO approval_definition
                      (id, app_code, flow_key, version, name, status, spec_jsonb, spec_hash, revision, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, 'DRAFT', CAST(? AS jsonb), ?, 1, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, id, app, flow, store.nextDefinitionVersion(app, flow), input.name(), store.json(input),
                    store.hash(input), caller.subject(), caller.subject()).update();
            if (changed == 0) throw error(APPROVAL_CONFLICT, "该流程已有待编辑草稿");
            return get(app, id);
        });
    }

    Definition save(Caller caller, String app, String id, long revision, Spec spec) {
        rules.validateSpec(app, spec, false);
        return edit(caller, app, id, revision, Status.DRAFT, current -> {
            store.sql("""
                    UPDATE approval_definition SET name = ?, spec_jsonb = CAST(? AS jsonb), spec_hash = ?,
                      revision = revision + 1, updated_by = ? WHERE id = ?
                    """, spec.name(), store.json(spec), store.hash(spec), caller.subject(), id).update();
        });
    }

    Definition publish(Caller caller, String app, String id, long revision) {
        return edit(caller, app, id, revision, Status.DRAFT, current -> {
            rules.validateSpec(app, current.spec(), true);
            store.sql("""
                    UPDATE approval_definition SET status = 'RETIRED', retired_by = ?, retired_at = now(),
                      revision = revision + 1, updated_by = ?
                    WHERE app_code = ? AND flow_key = ? AND status = 'ACTIVE'
                    """, caller.subject(), caller.subject(), app, current.flowKey()).update();
            store.sql("""
                    UPDATE approval_definition SET status = 'ACTIVE', published_at = now(), published_by = ?,
                      revision = revision + 1, updated_by = ? WHERE id = ?
                    """, caller.subject(), caller.subject(), id).update();
        });
    }

    Definition retire(Caller caller, String app, String id, long revision) {
        return edit(caller, app, id, revision, Status.ACTIVE, current -> store.sql("""
                UPDATE approval_definition SET status = 'RETIRED', retired_by = ?, retired_at = now(),
                  revision = revision + 1, updated_by = ? WHERE id = ?
                """, caller.subject(), caller.subject(), id).update());
    }

    private Definition edit(Caller caller, String app, String id, long revision, Status expected,
                            java.util.function.Consumer<Definition> operation) {
        permit(caller, app, "design");
        human(caller);
        return tx.execute(status -> {
            store.lockFlow(app, get(app, id).flowKey());
            Definition current = get(app, id);
            if (current.revision() != revision) throw error(APPROVAL_STALE, "流程版本已更新，请刷新");
            if (current.status() != expected) throw error(APPROVAL_CONFLICT, "当前流程状态无法执行此操作");
            operation.accept(current);
            return get(app, id);
        });
    }
}
