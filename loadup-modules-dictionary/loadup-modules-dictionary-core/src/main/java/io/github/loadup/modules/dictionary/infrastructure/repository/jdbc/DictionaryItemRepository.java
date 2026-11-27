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

import io.github.loadup.modules.dictionary.infrastructure.dataobject.DictionaryItemDO;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface DictionaryItemRepository extends Repository<DictionaryItemDO, String> {
    String ALL_ITEMS = """
        SELECT id, type_id, item_value, item_label, description, sort_order, enabled, created_at, updated_at
        FROM dictionary_item WHERE 1 = 1
        """;
    String ITEM_ORDER = " ORDER BY sort_order, item_label, item_value";

    @Query(ALL_ITEMS + " AND id = :id")
    Optional<DictionaryItemDO> findById(@Param("id") String id);

    @Query("SELECT COUNT(*) FROM dictionary_item WHERE type_id = :typeId")
    long countByTypeId(@Param("typeId") String typeId);

    @Query(ALL_ITEMS + " AND type_id = :typeId" + ITEM_ORDER + " LIMIT :limit OFFSET :offset")
    List<DictionaryItemDO> listByTypeId(
            @Param("typeId") String typeId, @Param("limit") int limit, @Param("offset") long offset);

    @Query(ALL_ITEMS + " AND type_id = :typeId AND enabled = TRUE" + ITEM_ORDER)
    List<DictionaryItemDO> listEnabled(@Param("typeId") String typeId);
}
