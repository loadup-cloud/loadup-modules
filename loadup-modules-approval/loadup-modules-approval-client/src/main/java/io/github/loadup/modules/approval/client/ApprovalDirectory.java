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
package io.github.loadup.modules.approval.client;

import static io.github.loadup.modules.approval.client.Approval.DirectoryType;
import static io.github.loadup.modules.approval.client.Approval.Option;
import static io.github.loadup.modules.approval.client.Approval.Person;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** An identity adapter must scope both lookup and enumeration to the registered application. */
public interface ApprovalDirectory {
    Optional<Person> person(String app, String userId);

    Stream<Person> people(String app);

    List<Option> search(String app, DirectoryType type, String query, int offset, int limit);

    boolean exists(String app, DirectoryType type, String id);

    default Optional<String> label(String app, DirectoryType type, String id) {
        if (type == DirectoryType.USER) {
            return person(app, id).map(Person::label);
        }
        return search(app, type, id, 0, 100).stream()
                .filter(option -> option.id().equals(id))
                .findFirst()
                .map(Option::label);
    }
}
