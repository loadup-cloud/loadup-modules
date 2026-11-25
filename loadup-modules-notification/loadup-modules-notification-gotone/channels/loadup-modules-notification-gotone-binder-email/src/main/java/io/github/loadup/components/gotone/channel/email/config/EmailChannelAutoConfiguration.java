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
package io.github.loadup.components.gotone.channel.email.config;

import io.github.loadup.components.gotone.NotificationChannelProvider;
import io.github.loadup.components.gotone.channel.email.SmtpEmailProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;

@AutoConfiguration(afterName = "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration")
@ConditionalOnSingleCandidate(JavaMailSender.class)
@ConditionalOnProperty(
        prefix = "loadup.gotone.binder.email.smtp",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailChannelAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "smtpEmailProvider")
    public NotificationChannelProvider smtpEmailProvider(
            JavaMailSender mailSender, @Value("${spring.mail.username:}") String defaultFrom) {
        return new SmtpEmailProvider(mailSender, defaultFrom);
    }
}
