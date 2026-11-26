package io.github.loadup.modules.gotone.infrastructure.repository;

import io.github.loadup.components.database.dataobject.BaseDO;
import io.github.loadup.components.database.id.IdGenerator;
import java.time.Clock;
import java.time.LocalDateTime;

/** Initializes the shared database columns on new Gotone records. */
public record GotoneRows(IdGenerator ids, Clock clock) {
    public <T extends BaseDO> T create(T row) {
        row.setId(ids.generate());
        row.setCreatedAt(LocalDateTime.now(clock));
        row.setUpdatedAt(row.getCreatedAt());
        row.setDeleted(false);
        return row;
    }

    <T extends BaseDO> T touch(T row) {
        row.setUpdatedAt(LocalDateTime.now(clock));
        return row;
    }
}
