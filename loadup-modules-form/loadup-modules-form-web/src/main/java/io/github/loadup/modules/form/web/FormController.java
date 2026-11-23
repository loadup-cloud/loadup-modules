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
package io.github.loadup.modules.form.web;

import static io.github.loadup.modules.form.client.FormDefinitions.*;
import static io.github.loadup.modules.form.client.FormInstances.*;

import io.github.loadup.commons.result.FailureResponse;
import io.github.loadup.commons.result.Result;
import io.github.loadup.commons.result.SuccessResponse;
import io.github.loadup.modules.form.client.FormCatalog;
import io.github.loadup.modules.form.client.FormException;
import io.github.loadup.modules.form.client.FormRuntime;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/forms/{namespace}")
public class FormController {
    private static final String AUTHORITY_PREFIX = "'form:' + #p0.toLowerCase(T(java.util.Locale).ROOT)";
    private static final String DESIGN = "hasAuthority(" + AUTHORITY_PREFIX + " + ':design')";
    private static final String PUBLISH = "hasAuthority(" + AUTHORITY_PREFIX + " + ':publish')";
    private static final String FILL = "hasAuthority(" + AUTHORITY_PREFIX + " + ':fill')";

    private final FormCatalog catalog;
    private final FormRuntime runtime;

    public FormController(FormCatalog catalog, FormRuntime runtime) {
        this.catalog = catalog;
        this.runtime = runtime;
    }

    public record PublishField(int version, Field field) {}
    public record Revision(long expectedRowVersion) {}
    public record ErrorDetail(String itemKey, String fieldKey, String reason) {}

    @GetMapping("/fields")
    @PreAuthorize(DESIGN)
    public SuccessResponse<List<FieldVersion>> fields(
            @PathVariable("namespace") String namespace,
            @RequestParam(name = "keyPrefix", required = false) String keyPrefix,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return SuccessResponse.of(catalog.fields(namespace, keyPrefix, offset, limit));
    }

    @PostMapping("/fields/publish")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<FieldVersion> publishField(
            @PathVariable("namespace") String namespace, @RequestBody PublishField request) {
        return SuccessResponse.of(catalog.publishField(namespace, request.version(), request.field()));
    }

    @GetMapping("/groups")
    @PreAuthorize(DESIGN)
    public SuccessResponse<List<Version<Group>>> groups(
            @PathVariable("namespace") String namespace,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return SuccessResponse.of(catalog.groups(namespace, offset, limit));
    }

    @GetMapping("/groups/{id}")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Group>> group(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id) {
        return SuccessResponse.of(catalog.group(namespace, id));
    }

    @PutMapping("/groups")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Group>> saveGroup(
            @PathVariable("namespace") String namespace, @RequestBody Save<Group> request) {
        return SuccessResponse.of(catalog.saveGroup(namespace, request));
    }

    @PostMapping("/groups/{id}/publish")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<Version<Group>> publishGroup(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, @RequestBody Revision request) {
        return SuccessResponse.of(catalog.publishGroup(namespace, id, request.expectedRowVersion()));
    }

    @GetMapping("/templates")
    @PreAuthorize(DESIGN)
    public SuccessResponse<List<Version<Template>>> templates(
            @PathVariable("namespace") String namespace,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return SuccessResponse.of(catalog.templates(namespace, offset, limit));
    }

    @GetMapping("/templates/{id}")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Template>> template(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id) {
        return SuccessResponse.of(catalog.template(namespace, id));
    }

    @PutMapping("/templates")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Template>> saveTemplate(
            @PathVariable("namespace") String namespace, @RequestBody Save<Template> request) {
        return SuccessResponse.of(catalog.saveTemplate(namespace, request));
    }

    @GetMapping("/templates/{id}/preview")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Release> preview(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id) {
        return SuccessResponse.of(catalog.preview(namespace, id));
    }

    @PostMapping("/templates/{id}/publish")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<Release> publishTemplate(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, @RequestBody Revision request) {
        return SuccessResponse.of(catalog.publishTemplate(namespace, id, request.expectedRowVersion()));
    }

    @PostMapping("/templates/{id}/retire")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<Void> retireTemplate(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, @RequestBody Revision request) {
        catalog.retireTemplate(namespace, id, request.expectedRowVersion());
        return SuccessResponse.success();
    }

    @PostMapping("/instances/start")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> start(
            @PathVariable("namespace") String namespace, Principal principal, @RequestBody Start request) {
        return SuccessResponse.of(runtime.start(namespace, principal.getName(), request));
    }

    @GetMapping("/instances/{id}")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> detail(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, Principal principal) {
        return SuccessResponse.of(runtime.detail(namespace, principal.getName(), id));
    }

    @PutMapping("/instances/draft")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> saveDraft(
            @PathVariable("namespace") String namespace, Principal principal, @RequestBody SaveDraft request) {
        return SuccessResponse.of(runtime.saveDraft(namespace, principal.getName(), request));
    }

    @PostMapping("/instances/submit")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> submit(
            @PathVariable("namespace") String namespace, Principal principal, @RequestBody Submit request) {
        return SuccessResponse.of(runtime.submit(namespace, principal.getName(), request));
    }

    @ExceptionHandler(FormException.class)
    public FailureResponse<ErrorDetail> formError(FormException exception) {
        return new FailureResponse<>(Result.buildFailure(exception.code()),
                new ErrorDetail(exception.itemKey(), exception.fieldKey(), exception.getMessage()), null);
    }
}
