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
package io.github.loadup.modules.gotone.client.facade;

import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Receipt;
import io.github.loadup.modules.gotone.client.Gotone.Request;
import io.github.loadup.modules.gotone.client.Gotone.Resolution;
import io.github.loadup.modules.gotone.client.Gotone.Retry;
import io.github.loadup.modules.gotone.client.Gotone.Route;
import io.github.loadup.modules.gotone.client.Gotone.SmsBinding;
import java.util.List;
import java.util.Map;

public interface GotoneOperations {
    List<Route> routes();

    List<Route> drafts(String actor);

    void saveDraft(Route route, String actor);

    Route publish(Route route, String actor);

    Map<String, String> preview(Route route, Map<String, String> parameters);

    Route restore(String serviceCode, int revision, int expectedVersion, String actor);

    List<Account> accounts();

    Account saveAccount(Account account);

    List<SmsBinding> smsBindings();

    void saveSmsBinding(SmsBinding binding);

    List<Receipt> recentRequests();

    Request request(String requestId);

    void retry(Retry retry, String actor);

    void resolveUnknown(Resolution resolution, String actor);
}
