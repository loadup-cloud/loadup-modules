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
package io.github.loadup.modules.upms.app.service;

import io.github.loadup.commons.log.LogUtil;
import io.github.loadup.commons.masking.MaskType;
import io.github.loadup.commons.masking.Masking;
import io.github.loadup.modules.upms.domain.model.VerificationCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class VerificationCodeService {
    private static final int CODE_LENGTH = 6;
    private static final int CODE_EXPIRY_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;
    private static final Random RANDOM = new SecureRandom();
    private final Clock clock;
    // In-memory storage (for production, use Redis)
    private final Map<String, VerificationCode> codeStorage = new ConcurrentHashMap<>();

    public VerificationCodeService(Clock clock) {
        this.clock = clock;
    }

    /** Generate verification code */
    public String generateCode(String target, String type) {
        String code = generateRandomCode();
        String key = buildKey(target, type);

        VerificationCode verificationCode = newCode(target, type, code);

        codeStorage.put(key, verificationCode);

        LogUtil.info(
                VerificationCodeService.class,
                "Generated verification code for {}",
                Masking.mask(target, type.equalsIgnoreCase("EMAIL") ? MaskType.EMAIL : MaskType.PHONE));
        return code;
    }

    Optional<String> issueResetCode(String userId, String target, String type) {
        var issued = new AtomicReference<String>();
        codeStorage.compute(resetKey(userId, target, type), (key, previous) -> {
            if (previous != null
                    && LocalDateTime.now(clock).isBefore(previous.getCreatedAt().plusMinutes(1))) {
                return previous;
            }
            String code = generateRandomCode();
            issued.set(code);
            return newCode(target, type, code);
        });
        return Optional.ofNullable(issued.get());
    }

    boolean consumeResetCode(String userId, String target, String type, String code) {
        var accepted = new AtomicBoolean();
        codeStorage.computeIfPresent(resetKey(userId, target, type), (key, current) -> {
            if (!LocalDateTime.now(clock).isBefore(current.getExpiryTime())) {
                return null;
            }
            if (current.getCode() != null && current.getAttempts() < MAX_ATTEMPTS) {
                current.setAttempts(current.getAttempts() + 1);
                if (current.getCode().equals(code)) {
                    current.setCode(null);
                    accepted.set(true);
                }
            }
            return current;
        });
        return accepted.get();
    }

    void discardResetCode(String userId, String target, String type, String code) {
        codeStorage.computeIfPresent(resetKey(userId, target, type), (key, current) -> {
            if (code.equals(current.getCode())) {
                current.setCode(null);
            }
            return current;
        });
    }

    private String resetKey(String userId, String target, String type) {
        return buildKey(userId + ":" + target, "PASSWORD_RESET:" + type);
    }

    private VerificationCode newCode(String target, String type, String code) {
        var now = LocalDateTime.now(clock);
        var verificationCode = new VerificationCode();
        verificationCode.setCode(code);
        verificationCode.setTarget(target);
        verificationCode.setType(type);
        verificationCode.setAttempts(0);
        verificationCode.setCreatedAt(now);
        verificationCode.setExpiryTime(now.plusMinutes(CODE_EXPIRY_MINUTES));
        return verificationCode;
    }

    /** Validate verification code */
    public boolean validateCode(String target, String type, String code) {
        String key = buildKey(target, type);
        VerificationCode verificationCode = codeStorage.get(key);

        if (verificationCode == null) {
            LogUtil.warn(
                    VerificationCodeService.class, "Verification code not found for: {}", Masking.keep(target, 1, 1));
            return false;
        }

        // Check expiry
        if (!LocalDateTime.ofInstant(clock.instant(), clock.getZone()).isBefore(verificationCode.getExpiryTime())) {
            LogUtil.warn(
                    VerificationCodeService.class, "Verification code expired for: {}", Masking.keep(target, 1, 1));
            codeStorage.remove(key);
            return false;
        }

        // Check attempts
        if (verificationCode.getAttempts() >= MAX_ATTEMPTS) {
            LogUtil.warn(
                    VerificationCodeService.class,
                    "Too many verification attempts for: {}",
                    Masking.keep(target, 1, 1));
            codeStorage.remove(key);
            return false;
        }

        // Validate code
        verificationCode.setAttempts(verificationCode.getAttempts() + 1);
        if (!code.equals(verificationCode.getCode())) {
            LogUtil.warn(
                    VerificationCodeService.class, "Invalid verification code for: {}", Masking.keep(target, 1, 1));
            return false;
        }

        // Code is valid, remove it
        codeStorage.remove(key);
        LogUtil.info(
                VerificationCodeService.class,
                "Verification code validated successfully for: {}",
                Masking.keep(target, 1, 1));
        return true;
    }

    /** Check if code can be sent (rate limiting) */
    public boolean canSendCode(String target, String type) {
        String key = buildKey(target, type);
        VerificationCode existingCode = codeStorage.get(key);

        if (existingCode == null) {
            return true;
        }

        // Check if previous code is still valid (prevent spamming)
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        LocalDateTime minResendTime = existingCode.getCreatedAt().plusMinutes(1);

        if (now.isBefore(minResendTime)) {
            LogUtil.warn(VerificationCodeService.class, "Too soon to resend code for: {}", Masking.keep(target, 1, 1));
            return false;
        }

        return true;
    }

    public boolean verifySmsCode(String mobile, String code) {
        return validateCode(mobile, "SMS", code);
    }

    public boolean verifyEmailCode(String email, String code) {
        return validateCode(email, "EMAIL", code);
    }

    public String generateSmsCode(String mobile) {
        return generateCode(mobile, "SMS");
    }

    public String generateEmailCode(String email) {
        return generateCode(email, "EMAIL");
    }

    /** Build storage key */
    private String buildKey(String target, String type) {
        return type + ":" + target;
    }

    /** Generate random numeric code */
    private String generateRandomCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(RANDOM.nextInt(10));
        }
        return code.toString();
    }
}
