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
package io.github.loadup.modules.transfer.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.transfer.infrastructure.repository.TransferGatewayImpl;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TransferGatewayQueuedTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransferGatewayImpl gateway = new TransferGatewayImpl(jdbc, () -> "id", Clock.systemDefaultZone());

    @Test
    void preservesCandidateOrderAndRejectsInvalidBatchSizes() {
        when(jdbc.query(any(String.class), any(RowMapper.class), any(Integer.class)))
                .thenReturn(List.of("first", "second"));
        assertThat(gateway.queued(2)).containsExactly("first", "second");
        clearInvocations(jdbc);
        for (int limit : new int[] {0, -1, 101}) {
            assertThatThrownBy(() -> gateway.queued(limit)).isInstanceOf(CommonException.class);
        }
    }
}
