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
package io.github.loadup.modules.dictionary.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.components.database.id.IdGenerator;
import io.github.loadup.components.testcontainers.annotation.ContainerType;
import io.github.loadup.components.testcontainers.annotation.EnableTestContainers;
import io.github.loadup.modules.dictionary.client.error.DictionaryResultCodeEnum;
import io.github.loadup.modules.dictionary.domain.gateway.DictionaryGateway;
import io.github.loadup.modules.dictionary.domain.model.DictionaryItem;
import io.github.loadup.modules.dictionary.domain.model.DictionaryType;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = DictionaryJdbcIT.Application.class,
        properties = {
            "loadup.database.schema=dictionary_it",
            "loadup.flyway.locations=classpath:db/migration/framework/dictionary"
        })
@EnableTestContainers(value = ContainerType.POSTGRESQL, reuse = false)
class DictionaryJdbcIT {
    @Autowired
    DictionaryGateway dictionary;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void jdbcWritesAndRepositoryReadsShareTheTransaction() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            dictionary.insertType(new DictionaryType(null, "rollback", "Rollback", null, true, null, null));
            assertThat(dictionary.findTypeById("generated-dictionary-id")).hasValueSatisfying(type -> {
                assertThat(type.code()).isEqualTo("rollback");
                assertThat(type.createdAt()).isNotNull().isEqualTo(type.updatedAt());
            });
            status.setRollbackOnly();
        });

        assertThat(dictionary.findTypeByCode("rollback")).isEmpty();
    }

    @Test
    void filtersDisabledItemsAndUsesExplicitPhysicalDeletes() {
        dictionary.insertType(new DictionaryType("type", "codes", "Codes", null, true, null, null));
        dictionary.insertItem(item("late", 20, true));
        dictionary.insertItem(item("disabled", 10, false));
        dictionary.insertItem(item("early", 10, true));
        assertThat(dictionary.listTypes(1, 10).total()).isOne();
        assertThat(dictionary.countItems("type")).isEqualTo(3);
        var firstPage = dictionary.listItems("type", 1, 2);
        assertThat(firstPage.total()).isEqualTo(3);
        assertThat(firstPage.records()).extracting(DictionaryItem::id).containsExactly("disabled", "early");
        assertThat(dictionary.listItems("type", 3, 2).records()).isEmpty();
        assertThat(dictionary.listEnabledItems("type"))
                .extracting(DictionaryItem::id)
                .containsExactly("early", "late");
        assertThatThrownBy(() -> dictionary.deleteType("type"))
                .isInstanceOfSatisfying(
                        CommonException.class,
                        error -> assertThat(error.getResultCode())
                                .isEqualTo(DictionaryResultCodeEnum.DICTIONARY_TYPE_NOT_EMPTY));

        dictionary.deleteItem("early");
        dictionary.insertItem(item("early", 10, true));
        dictionary.deleteItem("early");
        dictionary.deleteItem("late");
        dictionary.deleteItem("disabled");
        dictionary.deleteType("type");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM dictionary_type WHERE id = 'type'")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM dictionary_item WHERE type_id = 'type'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void concurrentInsertAndTypeDeleteCannotLeaveAnOrphan() throws Exception {
        dictionary.insertType(new DictionaryType("race-type", "race", "Race", null, true, null, null));
        var item = new DictionaryItem("race-item", "race-type", "value", "Value", null, 0, true, null, null);
        var start = new CyclicBarrier(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var insert = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    dictionary.insertItem(item);
                    return true;
                } catch (CommonException error) {
                    assertThat(error.getResultCode()).isEqualTo(DictionaryResultCodeEnum.DICTIONARY_TYPE_NOT_FOUND);
                    return false;
                }
            });
            var delete = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    dictionary.deleteType("race-type");
                    return true;
                } catch (CommonException error) {
                    assertThat(error.getResultCode()).isEqualTo(DictionaryResultCodeEnum.DICTIONARY_TYPE_NOT_EMPTY);
                    return false;
                }
            });

            boolean inserted = insert.get(10, TimeUnit.SECONDS);
            assertThat(delete.get(10, TimeUnit.SECONDS)).isEqualTo(!inserted);
            assertThat(dictionary.findTypeById("race-type").isPresent()).isEqualTo(inserted);
            assertThat(dictionary.findItemById("race-item").isPresent()).isEqualTo(inserted);
            if (inserted) {
                dictionary.deleteItem("race-item");
                dictionary.deleteType("race-type");
            }
        }
    }

    private static DictionaryItem item(String id, int sortOrder, boolean enabled) {
        return new DictionaryItem(id, "type", id, id, null, sortOrder, enabled, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {
        @Bean
        IdGenerator dictionaryTestIdGenerator() {
            return () -> "generated-dictionary-id";
        }
    }
}
