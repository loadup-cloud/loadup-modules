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

import static io.github.loadup.modules.form.client.FormDefinitions.Field;
import static io.github.loadup.modules.form.client.FormDefinitions.FieldVersion;
import static io.github.loadup.modules.form.client.FormDefinitions.Group;
import static io.github.loadup.modules.form.client.FormDefinitions.Release;
import static io.github.loadup.modules.form.client.FormDefinitions.Save;
import static io.github.loadup.modules.form.client.FormDefinitions.Template;
import static io.github.loadup.modules.form.client.FormDefinitions.Version;
import static io.github.loadup.modules.form.client.FormInstances.Detail;
import static io.github.loadup.modules.form.client.FormInstances.SaveDraft;
import static io.github.loadup.modules.form.client.FormInstances.Start;
import static io.github.loadup.modules.form.client.FormInstances.Submit;

import io.github.loadup.commons.result.FailureResponse;
import io.github.loadup.commons.result.Result;
import io.github.loadup.commons.result.SuccessResponse;
import io.github.loadup.modules.form.client.FormCatalog;
import io.github.loadup.modules.form.client.FormException;
import io.github.loadup.modules.form.client.FormRuntime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Forms", description = "Versioned form definitions, releases and form instances")
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

    @GetMapping("/fields")
    @Operation(summary = "List form field versions", description = "Optionally filter by field key prefix.")
    @PreAuthorize(DESIGN)
    public SuccessResponse<List<FieldVersion>> fields(
            @PathVariable("namespace") String namespace,
            @RequestParam(name = "keyPrefix", required = false) String keyPrefix,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return SuccessResponse.of(catalog.fields(namespace, keyPrefix, offset, limit));
    }

    @PostMapping("/fields/publish")
    @Operation(summary = "Publish a form field version")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<FieldVersion> publishField(
            @PathVariable("namespace") String namespace, @RequestBody PublishField request) {
        return SuccessResponse.of(catalog.publishField(namespace, request.version(), request.field()));
    }

    @GetMapping("/groups")
    @Operation(summary = "List field group versions")
    @PreAuthorize(DESIGN)
    public SuccessResponse<List<Version<Group>>> groups(
            @PathVariable("namespace") String namespace,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return SuccessResponse.of(catalog.groups(namespace, offset, limit));
    }

    @GetMapping("/groups/{id}")
    @Operation(summary = "Get a field group version")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Group>> group(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id) {
        return SuccessResponse.of(catalog.group(namespace, id));
    }

    @PutMapping("/groups")
    @Operation(summary = "Save a field group draft")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Group>> saveGroup(
            @PathVariable("namespace") String namespace, @RequestBody Save<Group> request) {
        return SuccessResponse.of(catalog.saveGroup(namespace, request));
    }

    @PostMapping("/groups/{id}/publish")
    @Operation(summary = "Publish a field group version")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<Version<Group>> publishGroup(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, @RequestBody Revision request) {
        return SuccessResponse.of(catalog.publishGroup(namespace, id, request.expectedRowVersion()));
    }

    @GetMapping("/templates")
    @Operation(summary = "List form template versions")
    @PreAuthorize(DESIGN)
    public SuccessResponse<List<Version<Template>>> templates(
            @PathVariable("namespace") String namespace,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return SuccessResponse.of(catalog.templates(namespace, offset, limit));
    }

    @GetMapping("/templates/{id}")
    @Operation(summary = "Get a form template version")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Template>> template(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id) {
        return SuccessResponse.of(catalog.template(namespace, id));
    }

    @PutMapping("/templates")
    @Operation(summary = "Save a form template draft")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Version<Template>> saveTemplate(
            @PathVariable("namespace") String namespace, @RequestBody Save<Template> request) {
        return SuccessResponse.of(catalog.saveTemplate(namespace, request));
    }

    @GetMapping("/templates/{id}/preview")
    @Operation(
            summary = "Preview the resolved form template",
            description = "Resolves the template version into renderable items and fields.")
    @PreAuthorize(DESIGN)
    public SuccessResponse<Release> preview(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id) {
        return SuccessResponse.of(catalog.preview(namespace, id));
    }

    @PostMapping("/templates/{id}/publish")
    @Operation(
            summary = "Publish a form template version",
            description = "Returns the immutable release used to start form instances.")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<Release> publishTemplate(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, @RequestBody Revision request) {
        return SuccessResponse.of(catalog.publishTemplate(namespace, id, request.expectedRowVersion()));
    }

    @PostMapping("/templates/{id}/retire")
    @Operation(
            summary = "Retire a form template version",
            description = "Stops creation of instances from this release. Existing drafts keep their release.")
    @PreAuthorize(PUBLISH)
    public SuccessResponse<Void> retireTemplate(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, @RequestBody Revision request) {
        catalog.retireTemplate(namespace, id, request.expectedRowVersion());
        return SuccessResponse.success();
    }

    @PostMapping("/instances/start")
    @Operation(
            summary = "Start a form instance",
            description = "Creates a draft from the release. Reuse the request key to retrieve the existing instance.")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> start(
            @PathVariable("namespace") String namespace, Principal principal, @RequestBody Start request) {
        return SuccessResponse.of(runtime.start(namespace, principal.getName(), request));
    }

    @GetMapping("/instances/{id}")
    @Operation(
            summary = "Get my form instance",
            description = "Includes the pinned release, selected items and resolved values.")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> detail(
            @PathVariable("namespace") String namespace, @PathVariable("id") String id, Principal principal) {
        return SuccessResponse.of(runtime.detail(namespace, principal.getName(), id));
    }

    @PutMapping("/instances/draft")
    @Operation(
            summary = "Save my form draft",
            description =
                    "Replaces the selected items and user overrides. Omitted overrides revert to inherited values.")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> saveDraft(
            @PathVariable("namespace") String namespace, Principal principal, @RequestBody SaveDraft request) {
        return SuccessResponse.of(runtime.saveDraft(namespace, principal.getName(), request));
    }

    @PostMapping("/instances/submit")
    @Operation(
            summary = "Submit my form instance",
            description =
                    "Validates required values and freezes the submitted values. Retry with the same request key and"
                            + " draft version.")
    @PreAuthorize(FILL)
    public SuccessResponse<Detail> submit(
            @PathVariable("namespace") String namespace, Principal principal, @RequestBody Submit request) {
        return SuccessResponse.of(runtime.submit(namespace, principal.getName(), request));
    }

    @ExceptionHandler(FormException.class)
    public FailureResponse<ErrorDetail> formError(FormException exception) {
        return new FailureResponse<>(
                Result.buildFailure(exception.code()),
                new ErrorDetail(exception.itemKey(), exception.fieldKey(), exception.getMessage()),
                null);
    }

    @Schema(name = "FormPublishField", description = "Field definition and its version number")
    public record PublishField(int version, Field field) {}

    @Schema(name = "FormRevision")
    public record Revision(
            @Schema(description = "Current rowVersion of the group or template", example = "1")
            long expectedRowVersion) {}

    @Schema(
            name = "FormErrorDetail",
            description = "Form failure details with the affected item and field when available")
    public record ErrorDetail(String itemKey, String fieldKey, String reason) {}
}
