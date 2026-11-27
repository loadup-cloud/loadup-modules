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
package io.github.loadup.modules.dictionary.infrastructure.repository;

import io.github.loadup.commons.domain.PageResult;
import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.modules.dictionary.client.error.DictionaryResultCodeEnum;
import io.github.loadup.modules.dictionary.domain.gateway.DictionaryGateway;
import io.github.loadup.modules.dictionary.domain.model.DictionaryItem;
import io.github.loadup.modules.dictionary.domain.model.DictionaryType;
import io.github.loadup.modules.dictionary.infrastructure.converter.DictionaryStorageConverter;
import io.github.loadup.modules.dictionary.infrastructure.repository.jdbc.DictionaryItemRepository;
import io.github.loadup.modules.dictionary.infrastructure.repository.jdbc.DictionaryTypeRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class DictionaryGatewayImpl implements DictionaryGateway {
    private final NamedParameterJdbcTemplate jdbc;
    private final DictionaryTypeRepository types;
    private final DictionaryItemRepository items;
    private final IdGenerator ids;
    private final DictionaryStorageConverter converter;
    private final Clock clock;

    public DictionaryGatewayImpl(
            NamedParameterJdbcTemplate jdbc,
            DictionaryTypeRepository types,
            DictionaryItemRepository items,
            IdGenerator ids,
            DictionaryStorageConverter converter,
            Clock clock) {
        this.jdbc = jdbc;
        this.types = types;
        this.items = items;
        this.ids = ids;
        this.converter = converter;
        this.clock = clock;
    }

    @Override
    public Optional<DictionaryType> findTypeById(String id) {
        return types.findById(id).map(converter::toDomain);
    }

    @Override
    public Optional<DictionaryType> findTypeByCode(String code) {
        return types.findByCode(code).map(converter::toDomain);
    }

    @Override
    public void insertType(DictionaryType type) {
        var row = converter.toDO(type);
        if (row.getId() == null || row.getId().isBlank()) {
            row.setId(ids.generate());
        }
        var now = LocalDateTime.now(clock);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        jdbc.update("""
            INSERT INTO dictionary_type (id, type_code, type_name, description, enabled, created_at, updated_at)
            VALUES (:id, :code, :name, :description, :enabled, :createdAt, :updatedAt)
            """, new BeanPropertySqlParameterSource(row));
    }

    @Override
    public void updateType(DictionaryType type) {
        var row = converter.toDO(type);
        row.setUpdatedAt(LocalDateTime.now(clock));
        int changed = jdbc.update(
                "UPDATE dictionary_type SET type_name = :name, description = :description, enabled = :enabled,"
                        + " updated_at = :updatedAt WHERE id = :id",
                new BeanPropertySqlParameterSource(row));
        if (changed != 1) {
            throw new CommonException(DictionaryResultCodeEnum.DICTIONARY_TYPE_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void deleteType(String id) {
        lockType(id);
        if (countItems(id) != 0) {
            throw new CommonException(DictionaryResultCodeEnum.DICTIONARY_TYPE_NOT_EMPTY);
        }
        jdbc.update("DELETE FROM dictionary_type WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public PageResult<DictionaryType> listTypes(int page, int size) {
        long total = types.countAll();
        var result = types.listAll(size, (page - 1L) * size);
        return new PageResult<>(result.stream().map(converter::toDomain).toList(), total, page, size);
    }

    @Override
    public Optional<DictionaryItem> findItemById(String id) {
        return items.findById(id).map(converter::toDomain);
    }

    @Override
    @Transactional
    public void insertItem(DictionaryItem item) {
        lockType(item.typeId());
        var row = converter.toDO(item);
        if (row.getId() == null || row.getId().isBlank()) {
            row.setId(ids.generate());
        }
        var now = LocalDateTime.now(clock);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        jdbc.update("""
            INSERT INTO dictionary_item (id, type_id, item_value, item_label, description, sort_order, enabled, created_at, updated_at)
            VALUES (:id, :typeId, :value, :label, :description, :sortOrder, :enabled, :createdAt, :updatedAt)
            """, new BeanPropertySqlParameterSource(row));
    }

    @Override
    public void updateItem(DictionaryItem item) {
        var row = converter.toDO(item);
        row.setUpdatedAt(LocalDateTime.now(clock));
        int changed = jdbc.update(
                "UPDATE dictionary_item SET item_label = :label, description = :description, sort_order = :sortOrder,"
                        + " enabled = :enabled, updated_at = :updatedAt WHERE id = :id",
                new BeanPropertySqlParameterSource(row));
        if (changed != 1) {
            throw new CommonException(DictionaryResultCodeEnum.DICTIONARY_ITEM_NOT_FOUND);
        }
    }

    @Override
    public void deleteItem(String id) {
        jdbc.update("DELETE FROM dictionary_item WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private void lockType(String id) {
        var args = new MapSqlParameterSource("id", id);
        if (jdbc.queryForList("SELECT id FROM dictionary_type WHERE id = :id FOR UPDATE", args, String.class)
                .isEmpty()) {
            throw new CommonException(DictionaryResultCodeEnum.DICTIONARY_TYPE_NOT_FOUND);
        }
    }

    @Override
    public long countItems(String typeId) {
        return items.countByTypeId(typeId);
    }

    @Override
    public PageResult<DictionaryItem> listItems(String typeId, int page, int size) {
        long total = countItems(typeId);
        var result = items.listByTypeId(typeId, size, (page - 1L) * size);
        return new PageResult<>(result.stream().map(converter::toDomain).toList(), total, page, size);
    }

    @Override
    public List<DictionaryItem> listEnabledItems(String typeId) {
        return items.listEnabled(typeId).stream().map(converter::toDomain).toList();
    }
}
