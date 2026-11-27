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
package io.github.loadup.modules.dictionary.infrastructure.repository.jdbc;

import io.github.loadup.modules.dictionary.infrastructure.dataobject.DictionaryTypeDO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface DictionaryTypeRepository extends Repository<DictionaryTypeDO, String> {
    String ALL_TYPES = """
        SELECT id, type_code, type_name, description, enabled, created_at, updated_at
        FROM dictionary_type WHERE 1 = 1
        """;

    @Query(ALL_TYPES + " AND id = :id")
    Optional<DictionaryTypeDO> findById(@Param("id") String id);

    @Query(ALL_TYPES + " AND type_code = :code")
    Optional<DictionaryTypeDO> findByCode(@Param("code") String code);

    @Query("SELECT COUNT(*) FROM dictionary_type WHERE 1 = 1")
    long countAll();

    @Query(ALL_TYPES + " ORDER BY type_code LIMIT :limit OFFSET :offset")
    List<DictionaryTypeDO> listAll(@Param("limit") int limit, @Param("offset") long offset);
}
