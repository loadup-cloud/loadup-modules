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
package io.github.loadup.modules.gotone.app.integration.email;

import io.github.loadup.modules.gotone.app.integration.GotoneProvider;
import io.github.loadup.modules.gotone.client.Gotone.Account;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneConfigurationStore.Binding;
import io.github.loadup.modules.gotone.infrastructure.repository.GotoneDeliveryStore.Work;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

public final class SmtpGotoneProvider implements GotoneProvider {
    @Override
    public String code() {
        return "smtp";
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public Set<String> settings() {
        return Set.of("host", "port", "tls", "username", "fromAddress", "fromName");
    }

    @Override
    public boolean valid(Account account) {
        var fields = account.settings();
        String port = fields.getOrDefault("port", "");
        return fields.getOrDefault("host", "").matches("[a-zA-Z0-9.-]{1,253}")
                && port.matches("[0-9]{1,5}")
                && Integer.parseInt(port) >= 1
                && Integer.parseInt(port) <= 65535
                && Set.of("STARTTLS", "SMTPS").contains(fields.getOrDefault("tls", ""))
                && !fields.getOrDefault("username", "").isBlank()
                && fields.getOrDefault("fromAddress", "").matches("[^@,;<>\\s]+@[^@,;<>\\s]+");
    }

    @Override
    public Outcome send(Account account, String secret, Work work, Binding binding) {
        var fields = account.settings();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        boolean secure = fields.get("tls").equals("SMTPS");
        String protocol = secure ? "smtps" : "smtp";
        String prefix = "mail." + protocol + ".";
        sender.setProtocol(protocol);
        sender.setHost(fields.get("host"));
        sender.setPort(Integer.parseInt(fields.get("port")));
        sender.setUsername(fields.get("username"));
        sender.setPassword(secret);
        var properties = sender.getJavaMailProperties();
        properties.setProperty(prefix + "auth", "true");
        properties.setProperty(prefix + "connectiontimeout", Integer.toString(account.connectTimeoutMs()));
        properties.setProperty(prefix + "timeout", Integer.toString(account.requestTimeoutMs()));
        properties.setProperty(prefix + "writetimeout", Integer.toString(account.requestTimeoutMs()));
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        if (!secure) {
            properties.setProperty(prefix + "starttls.enable", "true");
            properties.setProperty(prefix + "starttls.required", "true");
        }
        var message = sender.createMimeMessage();
        try {
            var content = work.snapshot().fields();
            String text = content.get("text");
            String html = content.get("html");
            var helper = new MimeMessageHelper(message, text != null && html != null, StandardCharsets.UTF_8.name());
            if (fields.containsKey("fromName")) {
                helper.setFrom(fields.get("fromAddress"), fields.get("fromName"));
            } else {
                helper.setFrom(fields.get("fromAddress"));
            }
            helper.setTo(work.snapshot().target().address());
            helper.setSubject(content.get("subject"));
            if (text != null && html != null) {
                helper.setText(text, html);
            } else {
                helper.setText(Objects.requireNonNullElse(text, html), html != null);
            }
            message.setHeader("Message-ID", "<" + work.id() + "@gotone>");
        } catch (java.io.UnsupportedEncodingException | MessagingException exception) {
            return Outcome.failed("INVALID_EMAIL");
        }
        try {
            sender.send(message);
            return Outcome.sent("<" + work.id() + "@gotone>");
        } catch (MailAuthenticationException exception) {
            return Outcome.retry("SMTP_AUTHENTICATION");
        } catch (MailSendException exception) {
            for (Exception failure : exception.getMessageExceptions()) {
                // Single-recipient messages may be routed elsewhere after an explicit rejection.
                if (failure instanceof SendFailedException rejected
                        && (rejected.getValidSentAddresses() == null || rejected.getValidSentAddresses().length == 0)
                        && rejected.getInvalidAddresses() != null
                        && rejected.getInvalidAddresses().length > 0) {
                    return Outcome.failed("SMTP_RECIPIENT_REJECTED");
                }
                if (failure.getCause() instanceof UnknownHostException
                        || failure.getCause() instanceof ConnectException) {
                    return Outcome.retry("SMTP_CONNECT_FAILED");
                }
            }
            return Outcome.unknown("NO_SMTP_RESULT");
        } catch (RuntimeException exception) {
            return Outcome.unknown("NO_SMTP_RESULT");
        }
    }
}
