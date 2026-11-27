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

import io.github.loadup.modules.upms.domain.gateway.DepartmentGateway;
import io.github.loadup.modules.upms.domain.model.Department;
import io.github.loadup.modules.upms.infrastructure.converter.DepartmentConverter;
import io.github.loadup.modules.upms.infrastructure.dataobject.DepartmentDO;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DepartmentGatewayImpl implements DepartmentGateway {
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcClient queries;
    private final Clock clock;
    private final DepartmentConverter converter;

    public DepartmentGatewayImpl(NamedParameterJdbcTemplate jdbc, DepartmentConverter converter, Clock clock) {
        this.jdbc = jdbc;
        this.queries = JdbcClient.create(jdbc);
        this.clock = clock;
        this.converter = converter;
    }

    @Override
    @Transactional
    public Department save(Department department) {
        DepartmentDO row = converter.toDataObject(department);
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
            INSERT INTO upms_department (id, parent_id, dept_name, dept_code, dept_level,
                sort_order, leader_user_id, phone, email, status, remark, created_by, updated_by,
                created_at, updated_at, deleted)
            VALUES (:id, :parentId, :deptName, :deptCode, COALESCE(:deptLevel, 1),
                COALESCE(:sortOrder, 0), :leaderUserId, :mobile, :email, COALESCE(:status, 1),
                :remark, :createdBy, :updatedBy, :createdAt, :updatedAt, FALSE)
            """, new BeanPropertySqlParameterSource(row));
        return converter.toEntity(row);
    }

    @Override
    @Transactional
    public Department update(Department department) {
        DepartmentDO row = converter.toDataObject(department);
        lockParent(row.getParentId());
        row.setUpdatedAt(LocalDateTime.now(clock));
        int changed = jdbc.update("""
            UPDATE upms_department SET parent_id = COALESCE(:parentId, parent_id),
                dept_name = COALESCE(:deptName, dept_name), dept_code = COALESCE(:deptCode, dept_code),
                dept_level = COALESCE(:deptLevel, dept_level), sort_order = COALESCE(:sortOrder, sort_order),
                leader_user_id = COALESCE(:leaderUserId, leader_user_id), phone = COALESCE(:mobile, phone),
                email = COALESCE(:email, email), status = COALESCE(:status, status),
                remark = COALESCE(:remark, remark), updated_by = COALESCE(:updatedBy, updated_by), updated_at = :updatedAt
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
        var values = params().addValue("id", id).addValue("now", LocalDateTime.now(clock));
        jdbc.queryForList(
                "SELECT id FROM upms_department WHERE id = :id AND deleted = FALSE FOR UPDATE", values, String.class);
        if (hasChildren(id) || hasUsers(id)) {
            throw new IllegalStateException("Department still has users or child departments");
        }
        jdbc.update(
                "UPDATE upms_role_department SET deleted = TRUE, updated_at = :now WHERE dept_id = :id AND deleted ="
                        + " FALSE",
                values);
        jdbc.update(
                "UPDATE upms_department SET deleted = TRUE, updated_at = :now WHERE id = :id AND deleted = FALSE",
                values);
    }

    @Override
    public Optional<Department> findById(String id) {
        return queries.sql("SELECT d.*, d.phone AS mobile FROM upms_department d WHERE d.deleted = FALSE AND id = :id")
                .param("id", id)
                .query(DepartmentDO.class)
                .optional()
                .map(converter::toEntity);
    }

    @Override
    public Optional<Department> findByDeptCode(String deptCode) {
        return queries.sql(
                        "SELECT d.*, d.phone AS mobile FROM upms_department d WHERE d.deleted = FALSE AND dept_code = :dept_code")
                .param("dept_code", deptCode)
                .query(DepartmentDO.class)
                .optional()
                .map(converter::toEntity);
    }

    @Override
    public List<Department> findByParentId(String parentId) {
        return queries
                .sql(
                        "SELECT d.*, d.phone AS mobile FROM upms_department d WHERE d.deleted = FALSE AND parent_id = :parent_id")
                .param("parent_id", parentId)
                .query(DepartmentDO.class)
                .list()
                .stream()
                .map(converter::toEntity)
                .toList();
    }

    @Override
    public List<Department> findAll() {
        return queries
                .sql("SELECT d.*, d.phone AS mobile FROM upms_department d WHERE d.deleted = FALSE AND TRUE")
                .query(DepartmentDO.class)
                .list()
                .stream()
                .map(converter::toEntity)
                .toList();
    }

    @Override
    public List<Department> findAllEnabled() {
        return queries
                .sql(
                        "SELECT d.*, d.phone AS mobile FROM upms_department d WHERE d.deleted = FALSE AND status = :status")
                .param("status", (short) 1)
                .query(DepartmentDO.class)
                .list()
                .stream()
                .map(converter::toEntity)
                .toList();
    }

    @Override
    public List<Department> findRootDepartments() {
        return findAll().stream()
                .filter(dept -> dept.getParentId() == null || "0".equals(dept.getParentId()))
                .toList();
    }

    @Override
    public boolean existsByDeptCode(String deptCode) {
        return queries.sql("SELECT COUNT(*) FROM upms_department WHERE deleted = FALSE AND dept_code = :dept_code")
                        .param("dept_code", deptCode)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public boolean hasChildren(String deptId) {
        return queries.sql("SELECT COUNT(*) FROM upms_department WHERE deleted = FALSE AND parent_id = :parent_id")
                        .param("parent_id", deptId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public boolean hasUsers(String deptId) {
        return queries.sql("SELECT COUNT(*) FROM upms_user WHERE deleted = FALSE AND dept_id = :dept_id")
                        .param("dept_id", deptId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    public List<Department> buildTree() {
        List<Department> departments = findAll();
        return departments.stream()
                .filter(dept -> dept.getParentId() == null || "0".equals(dept.getParentId()))
                .peek(dept -> buildChildren(dept, departments))
                .toList();
    }

    private void buildChildren(Department parent, List<Department> departments) {
        parent.setChildren(departments.stream()
                .filter(dept -> parent.getId().equals(dept.getParentId()))
                .peek(child -> buildChildren(child, departments))
                .toList());
    }

    private void lockParent(String parentId) {
        if (parentId == null || "0".equals(parentId)) {
            return;
        }
        if (jdbc.queryForList(
                        "SELECT id FROM upms_department WHERE id = :id AND deleted = FALSE AND status = 1 FOR UPDATE",
                        params().addValue("id", parentId),
                        String.class)
                .isEmpty()) {
            throw new IllegalArgumentException("Active parent department not found");
        }
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource();
    }
}
