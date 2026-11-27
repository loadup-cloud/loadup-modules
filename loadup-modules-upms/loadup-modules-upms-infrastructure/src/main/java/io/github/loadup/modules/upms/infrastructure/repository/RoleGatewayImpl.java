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
package io.github.loadup.modules.upms.infrastructure.repository;

import io.github.loadup.commons.domain.PageResult;
import io.github.loadup.commons.dto.PageQuery;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.upms.client.error.UpmsResultCodeEnum;
import io.github.loadup.modules.upms.domain.gateway.RoleGateway;
import io.github.loadup.modules.upms.domain.model.Role;
import io.github.loadup.modules.upms.infrastructure.converter.RoleConverter;
import io.github.loadup.modules.upms.infrastructure.dataobject.RoleDO;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoleGatewayImpl implements RoleGateway {
    private static final String FROM = " FROM upms_role r WHERE r.deleted = FALSE AND ";
    private static final String COLUMNS =
            "r.id, r.role_name, r.role_code, r.parent_role_id AS parent_id, r.role_level, r.data_scope, r.sort_order,"
                    + " r.status, r.remark, r.created_by, r.updated_by, r.created_at, r.updated_at, r.deleted";
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcClient queries;
    private final Clock clock;
    private final RoleConverter converter;

    public RoleGatewayImpl(NamedParameterJdbcTemplate jdbc, RoleConverter converter, Clock clock) {
        this.jdbc = jdbc;
        this.queries = JdbcClient.create(jdbc);
        this.clock = clock;
        this.converter = converter;
    }

    @Override
    @Transactional
    public Role save(Role role) {
        RoleDO row = converter.toDataObject(role);
        lockParent(row.getParentId());
        if (row.getId() == null) {
            row.setId(UUID.randomUUID().toString());
        }
        var now = LocalDateTime.now(clock);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(now);
        }
        row.setUpdatedAt(now);
        jdbc.update("""
            INSERT INTO upms_role (id, role_name, role_code, parent_role_id, role_level, data_scope,
                sort_order, status, remark, created_by, updated_by, created_at, updated_at, deleted)
            VALUES (:id, :roleName, :roleCode, :parentId, COALESCE(:roleLevel, 1), COALESCE(:dataScope, 1),
                COALESCE(:sortOrder, 0), COALESCE(:status, 1), :remark, :createdBy, :updatedBy, :createdAt, :updatedAt, FALSE)
            """, new BeanPropertySqlParameterSource(row));
        return converter.toEntity(row);
    }

    @Override
    @Transactional
    public Role update(Role role) {
        RoleDO row = converter.toDataObject(role);
        lockParent(row.getParentId());
        row.setUpdatedAt(LocalDateTime.now(clock));
        int changed = jdbc.update("""
            UPDATE upms_role SET role_name = COALESCE(:roleName, role_name), role_code = COALESCE(:roleCode, role_code),
                parent_role_id = COALESCE(:parentId, parent_role_id), role_level = COALESCE(:roleLevel, role_level),
                data_scope = COALESCE(:dataScope, data_scope), sort_order = COALESCE(:sortOrder, sort_order),
                status = COALESCE(:status, status), remark = COALESCE(:remark, remark),
                updated_by = COALESCE(:updatedBy, updated_by), updated_at = :updatedAt
            WHERE id = :id AND deleted = FALSE
            """, new BeanPropertySqlParameterSource(row));
        if (changed != 1) {
            throw new IncorrectResultSizeDataAccessException(1, changed);
        }
        return converter.toEntity(row);
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        var params = params().addValue("id", id).addValue("now", LocalDateTime.now(clock));
        jdbc.queryForList(
                "SELECT id FROM upms_role WHERE id = :id AND deleted = FALSE FOR UPDATE", params, String.class);
        if (!findByParentId(id).isEmpty()) {
            throw new CommonException(UpmsResultCodeEnum.ROLE_STILL_HAS_CHILD_ROLES);
        }
        if (countUsersByRoleId(id) > 0) {
            throw new CommonException(UpmsResultCodeEnum.ROLE_IS_STILL_ASSIGNED_TO_USERS);
        }
        for (String table : List.of("upms_role_permission", "upms_role_department", "upms_user_role")) {
            jdbc.update(
                    "UPDATE "
                            + table
                            + " SET deleted = TRUE, updated_at = :now WHERE role_id = :id AND deleted = FALSE",
                    params);
        }
        jdbc.update(
                "UPDATE upms_role SET deleted = TRUE, updated_at = :now WHERE id = :id AND deleted = FALSE", params);
    }

    @Override
    public Optional<Role> findById(String id) {
        return queries.sql("SELECT " + COLUMNS + FROM + "id = :id")
                .param("id", id)
                .query(RoleDO.class)
                .optional()
                .map(converter::toEntity);
    }

    @Override
    public Optional<Role> findByRoleCode(String roleCode) {
        return queries.sql("SELECT " + COLUMNS + FROM + "role_code = :role_code")
                .param("role_code", roleCode)
                .query(RoleDO.class)
                .optional()
                .map(converter::toEntity);
    }

    @Override
    public List<Role> findByUserId(String userId) {
        return find(
                "EXISTS (SELECT 1 FROM upms_user_role ur WHERE ur.role_id = r.id AND ur.user_id = :value AND"
                        + " ur.deleted = FALSE)",
                userId);
    }

    @Override
    public List<Role> findByParentId(String parentId) {
        return queries
                .sql("SELECT " + COLUMNS + FROM + "parent_role_id = :parent_role_id")
                .param("parent_role_id", parentId)
                .query(RoleDO.class)
                .list()
                .stream()
                .map(converter::toEntity)
                .toList();
    }

    @Override
    public List<Role> findAll() {
        return queries.sql("SELECT " + COLUMNS + FROM + "TRUE").query(RoleDO.class).list().stream()
                .map(converter::toEntity)
                .toList();
    }

    @Override
    public List<Role> findAllEnabled() {
        return queries
                .sql("SELECT " + COLUMNS + FROM + "status = :status")
                .param("status", (short) 1)
                .query(RoleDO.class)
                .list()
                .stream()
                .map(converter::toEntity)
                .toList();
    }

    @Override
    public boolean existsByRoleCode(String roleCode) {
        return queries.sql("SELECT COUNT(*) FROM upms_role WHERE deleted = FALSE AND role_code = :role_code")
                        .param("role_code", roleCode)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    @Transactional
    public void assignRoleToUser(String userId, String roleId, String operatorId) {
        lockActive("upms_role", roleId);
        lockActive("upms_user", userId);
        insertRelation("upms_user_role", "user_id", userId, "role_id", roleId, operatorId);
    }

    @Override
    public void removeRoleFromUser(String userId, String roleId) {
        removeRelation("upms_user_role", "user_id", userId, "role_id", List.of(roleId));
    }

    @Override
    public List<String> getUserRoleIds(String userId) {
        return queries.sql("SELECT role_id FROM upms_user_role WHERE deleted = FALSE AND user_id = :id")
                .param("id", userId)
                .query(String.class)
                .list();
    }

    @Override
    @Transactional
    public void assignPermissionsToRole(String roleId, List<String> permissionIds) {
        lockActive("upms_role", roleId);
        for (String permissionId : permissionIds.stream().distinct().sorted().toList()) {
            lockActive("upms_permission", permissionId);
            insertRelation("upms_role_permission", "role_id", roleId, "permission_id", permissionId, null);
        }
    }

    @Override
    public void removePermissionsFromRole(String roleId, List<String> permissionIds) {
        removeRelation("upms_role_permission", "role_id", roleId, "permission_id", permissionIds);
    }

    @Override
    @Transactional
    public void assignDepartmentsToRole(String roleId, List<String> departmentIds) {
        lockActive("upms_role", roleId);
        for (String deptId : departmentIds.stream().distinct().sorted().toList()) {
            lockActive("upms_department", deptId);
            insertRelation("upms_role_department", "role_id", roleId, "dept_id", deptId, null);
        }
    }

    @Override
    public void removeDepartmentsFromRole(String roleId, List<String> departmentIds) {
        removeRelation("upms_role_department", "role_id", roleId, "dept_id", departmentIds);
    }

    @Override
    public List<String> findDepartmentIdsByRoleId(String roleId) {
        return ids("upms_role_department", "dept_id", "role_id", roleId);
    }

    @Override
    public PageResult<Role> findAll(PageQuery query) {
        long total = queries.sql("SELECT COUNT(*) FROM upms_role WHERE deleted = FALSE")
                .query(Long.class)
                .single();
        var args = params().addValue("limit", query.pageSize())
                .addValue("offset", (query.pageNum() - 1L) * query.pageSize());
        var roles = select("TRUE", args, " ORDER BY r.id LIMIT :limit OFFSET :offset");
        return PageResult.of(roles, total, query.pageNum(), query.pageSize());
    }

    @Override
    public long countUsersByRoleId(String roleId) {
        return queries.sql("SELECT COUNT(*) FROM upms_user_role WHERE deleted = FALSE AND role_id = :role_id")
                .param("role_id", roleId)
                .query(Long.class)
                .single();
    }

    private void lockParent(String id) {
        if (id == null) {
            return;
        }
        if (jdbc.queryForList(
                        "SELECT id FROM upms_role WHERE id = :id AND deleted = FALSE FOR UPDATE",
                        params().addValue("id", id),
                        String.class)
                .isEmpty()) {
            throw new CommonException(UpmsResultCodeEnum.PARENT_ROLE_DOES_NOT_EXIST);
        }
    }

    private void lockActive(String table, String id) {
        var rows = jdbc.queryForList(
                "SELECT id FROM " + table + " WHERE id = :id AND deleted = FALSE AND status = 1 FOR UPDATE",
                params().addValue("id", id),
                String.class);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Active related entity not found: " + table);
        }
    }

    private void insertRelation(
            String table, String firstColumn, String first, String secondColumn, String second, String operator) {
        var values = params().addValue("id", UUID.randomUUID().toString())
                .addValue("first", first)
                .addValue("second", second)
                .addValue("operator", operator)
                .addValue("now", LocalDateTime.now(clock));
        int changed = jdbc.update(
                "INSERT INTO "
                        + table
                        + " (id, "
                        + firstColumn
                        + ", "
                        + secondColumn
                        + ", created_by, created_at, updated_at, deleted) "
                        + "VALUES (:id, :first, :second, :operator, :now, :now, FALSE) ON CONFLICT ("
                        + firstColumn
                        + ", "
                        + secondColumn
                        + ") "
                        + "DO UPDATE SET deleted = FALSE, updated_at = EXCLUDED.updated_at",
                values);
        if (changed != 1) {
            throw new IllegalStateException("Relation was not saved");
        }
    }

    private void removeRelation(
            String table, String firstColumn, String first, String secondColumn, List<String> seconds) {
        if (seconds.isEmpty()) {
            return;
        }
        jdbc.update(
                "UPDATE "
                        + table
                        + " SET deleted = TRUE, updated_at = :now WHERE "
                        + firstColumn
                        + " = :first AND "
                        + secondColumn
                        + " IN (:seconds) AND deleted = FALSE",
                params().addValue("first", first)
                        .addValue("seconds", seconds)
                        .addValue("now", LocalDateTime.now(clock)));
    }

    private List<String> ids(String table, String resultColumn, String keyColumn, String key) {
        return jdbc.queryForList(
                "SELECT " + resultColumn + " FROM " + table + " WHERE " + keyColumn + " = :key AND deleted = FALSE",
                params().addValue("key", key),
                String.class);
    }

    private List<Role> find(String predicate, Object value) {
        return select(predicate, params().addValue("value", value), "");
    }

    private List<Role> select(String predicate, MapSqlParameterSource params, String suffix) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + FROM + predicate + suffix,
                        params,
                        BeanPropertyRowMapper.newInstance(RoleDO.class))
                .stream()
                .map(converter::toEntity)
                .toList();
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource();
    }
}
