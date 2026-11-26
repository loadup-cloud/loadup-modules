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
package io.github.loadup.modules.gotone.app.service;

import static io.github.loadup.modules.gotone.client.error.GotoneResultCode.INVALID_TEMPLATE;

import io.github.loadup.commons.error.CommonException;
import io.github.loadup.modules.gotone.client.Gotone.Channel;
import io.github.loadup.modules.gotone.client.Gotone.Template;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

public final class GotoneTemplates {
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]{0,63})}");
    private final JsonMapper json;

    public GotoneTemplates(JsonMapper json) {
        this.json = json;
    }

    public void validate(Template template, Set<String> parameters) {
        for (var field : template.fields().entrySet()) {
            if (field.getValue().length() > 16000) throw invalid();
            var matches = EXPRESSION.matcher(field.getValue());
            while (matches.find()) {
                int start = matches.start();
                if (!parameters.contains(matches.group(1)) || (field.getKey().equals("html")
                        && field.getValue().lastIndexOf('<', start) > field.getValue().lastIndexOf('>', start))) {
                    throw invalid();
                }
            }
        }
        Map<String, String> sample = new TreeMap<>();
        parameters.forEach(key -> sample.put(key, key.equals("actionUrl") ? "/preview" : "preview"));
        render(template, sample);
    }

    public Map<String, String> render(Template template, Map<String, String> parameters) {
        Map<String, String> rendered = new TreeMap<>();
        template.fields().forEach((key, value) -> {
            Function<String, String> escape = switch (key) {
                case "html" -> HtmlUtils::htmlEscape;
                case "payload" -> this::escapeJson;
                default -> Function.identity();
            };
            String result = EXPRESSION.matcher(value).replaceAll(match -> {
                String parameter = parameters.get(match.group(1));
                if (parameter == null) throw invalid();
                return java.util.regex.Matcher.quoteReplacement(escape.apply(parameter));
            });
            if (result.length() > 32000) throw invalid();
            rendered.put(key, result);
        });
        switch (template.channel()) {
            case SMS -> required(rendered, "body", 1600);
            case IN_APP -> {
                required(rendered, "title", 200);
                required(rendered, "body", 4000);
                required(rendered, "category", 64);
                String path = rendered.get("actionPath");
                if (path != null && !path.isBlank() && (path.length() > 500 || !path.startsWith("/")
                        || path.startsWith("//") || path.contains("\\") || path.chars().anyMatch(Character::isISOControl))) {
                    throw invalid();
                }
            }
            case EMAIL -> {
                required(rendered, "subject", 200);
                if (rendered.get("subject").chars().anyMatch(Character::isISOControl)
                        || (rendered.getOrDefault("text", "").isBlank() && rendered.getOrDefault("html", "").isBlank())) {
                    throw invalid();
                }
            }
            case PUSH -> {
                required(rendered, "title", 200);
                required(rendered, "body", 4000);
                String ttl = rendered.getOrDefault("ttlSeconds", "3600");
                if (!ttl.matches("[0-9]{1,7}") || Integer.parseInt(ttl) > 2419200) throw invalid();
                String platform = rendered.get("platform");
                if (platform != null && !Set.of("ANDROID", "IOS", "WEB").contains(platform)) throw invalid();
            }
            case WEBHOOK -> {
                required(rendered, "endpointCode", 64);
                try {
                    if (!json.readTree(required(rendered, "payload", 32000)).isObject()) throw invalid();
                } catch (RuntimeException exception) {
                    throw invalid();
                }
            }
        }
        return rendered;
    }

    public static Set<String> fields(Channel channel) {
        return switch (channel) {
            case SMS -> Set.of("body", "templateCode");
            case EMAIL -> Set.of("subject", "text", "html");
            case IN_APP -> Set.of("category", "title", "body", "actionPath");
            case PUSH -> Set.of("title", "body", "platform", "ttlSeconds");
            case WEBHOOK -> Set.of("endpointCode", "payload");
        };
    }

    private String escapeJson(String value) {
        String encoded = json.writeValueAsString(value);
        return encoded.substring(1, encoded.length() - 1);
    }

    private static String required(Map<String, String> fields, String key, int max) {
        String value = fields.get(key);
        if (value == null || value.isBlank() || value.length() > max) throw invalid();
        return value;
    }

    private static CommonException invalid() {
        return new CommonException(INVALID_TEMPLATE);
    }
}
