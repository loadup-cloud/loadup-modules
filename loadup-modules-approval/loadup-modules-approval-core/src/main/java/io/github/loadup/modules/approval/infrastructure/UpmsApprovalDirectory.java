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
package io.github.loadup.modules.approval.infrastructure;

import static io.github.loadup.modules.approval.client.Approval.*;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.approval.client.ApprovalDirectory;
import io.github.loadup.modules.upms.client.dto.DepartmentDTO;
import io.github.loadup.modules.upms.client.dto.RoleDTO;
import io.github.loadup.modules.upms.client.dto.UserDetailDTO;
import io.github.loadup.modules.upms.client.facade.AccountSecurityFacade;
import io.github.loadup.modules.upms.client.facade.DepartmentFacade;
import io.github.loadup.modules.upms.client.facade.RoleFacade;
import io.github.loadup.modules.upms.client.facade.UserFacade;
import io.github.loadup.modules.upms.client.query.UserPageQuery;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class UpmsApprovalDirectory implements ApprovalDirectory {
    private final UserFacade users;
    private final RoleFacade roles;
    private final DepartmentFacade departments;
    private final AccountSecurityFacade accounts;

    public UpmsApprovalDirectory(
            UserFacade users, RoleFacade roles, DepartmentFacade departments, AccountSecurityFacade accounts) {
        this.users = users;
        this.roles = roles;
        this.departments = departments;
        this.accounts = accounts;
    }

    @Override
    public Optional<Person> person(String app, String id) {
        if (id == null) return Optional.empty();
        try {
            return Optional.ofNullable(users.getUserById(id)).filter(this::eligibleAccount).map(this::person);
        } catch (CommonException e) {
            if (missing(e)) return Optional.empty();
            throw e;
        }
    }

    @Override
    public Stream<Person> people(String app) {
        Iterator<UserDetailDTO> rows = new Iterator<>() {
            private int page = 1;
            private Iterator<UserDetailDTO> batch = List.<UserDetailDTO>of().iterator();
            private boolean more = true;

            @Override
            public boolean hasNext() {
                if (!batch.hasNext() && more) {
                    Collection<UserDetailDTO> items = users.queryUsers(UserPageQuery.builder()
                            .page(page++).size(100).sortBy("id").sortOrder("ASC").build()).getData();
                    more = items.size() == 100;
                    batch = items.iterator();
                }
                return batch.hasNext();
            }

            @Override
            public UserDetailDTO next() {
                hasNext();
                return batch.next();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(rows, 0), false)
                .filter(this::eligibleAccount).map(this::person);
    }

    @Override
    public List<Option> search(String app, DirectoryType type, String input, int offset, int limit) {
        String query = input == null ? "" : input.toLowerCase(Locale.ROOT);
        return options(app, type).filter(option -> option.id().toLowerCase(Locale.ROOT).contains(query)
                || option.label().toLowerCase(Locale.ROOT).contains(query)).skip(offset).limit(limit).toList();
    }

    @Override
    public boolean exists(String app, DirectoryType type, String id) {
        if (type == DirectoryType.USER) return person(app, id).isPresent();
        return options(app, type).anyMatch(option -> option.id().equals(id));
    }

    @Override
    public Optional<String> label(String app, DirectoryType type, String id) {
        if (type == DirectoryType.USER) {
            try {
                return Optional.ofNullable(users.getUserById(id)).map(user -> person(user).label());
            } catch (CommonException e) {
                if (missing(e)) return Optional.empty();
                throw e;
            }
        }
        return options(app, type).filter(option -> option.id().equals(id)).findFirst().map(Option::label);
    }

    private Stream<Option> options(String app, DirectoryType type) {
        return switch (type) {
            case USER -> people(app).map(person -> new Option(person.id(), person.label()));
            case ROLE -> roleTree(roles.getRoleTree()).filter(role -> active(role.getStatus()))
                    .map(role -> new Option(role.getId(), role.getRoleName()));
            case DEPARTMENT -> departmentTree(departments.getDepartmentTree()).filter(dept -> active(dept.getStatus()))
                    .map(dept -> new Option(dept.getId(), dept.getDeptName()));
        };
    }

    private Person person(UserDetailDTO user) {
        String label = user.getUsername();
        if (user.getNickname() != null && !user.getNickname().equals(label)) label += " · " + user.getNickname();
        if (user.getDeptName() != null) label += " · " + user.getDeptName();
        var roleIds = user.getRoles() == null ? Stream.<RoleDTO>empty() : user.getRoles().stream();
        return new Person(user.getId(), label, user.getDeptId(), roleIds.filter(role -> active(role.getStatus()))
                .map(RoleDTO::getId).collect(Collectors.toSet()));
    }

    private boolean eligibleAccount(UserDetailDTO user) {
        if (!active(user.getStatus())) return false;
        try {
            var account = accounts.overview(user.getId());
            return account.active() && account.accountNonLocked();
        } catch (CommonException e) {
            if (missing(e)) return false;
            throw e;
        }
    }

    private boolean missing(CommonException exception) {
        return switch (exception.getResultCode().getCode()) {
            case "UPMS_USER_NOT_FOUND", "UPMS_USER_NOT_FOUND_BBCDE8" -> true;
            default -> false;
        };
    }

    private boolean active(Short status) {
        return status != null && status == 1;
    }

    private Stream<RoleDTO> roleTree(List<RoleDTO> items) {
        return items == null ? Stream.empty() : items.stream()
                .flatMap(role -> Stream.concat(Stream.of(role), roleTree(role.getChildren())));
    }

    private Stream<DepartmentDTO> departmentTree(List<DepartmentDTO> items) {
        return items == null ? Stream.empty() : items.stream()
                .flatMap(dept -> Stream.concat(Stream.of(dept), departmentTree(dept.getChildren())));
    }
}
