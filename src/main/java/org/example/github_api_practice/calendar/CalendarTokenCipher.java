package org.example.github_api_practice.calendar;

import org.example.github_api_practice.global.error.ApiException;
import org.example.github_api_practice.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class CalendarTokenCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CalendarTokenCipher(@Value("${calendar.token-encryption-key:}") String encodedKey) {
        if (encodedKey.isBlank()) {
            key = null;
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encodedKey);
            if (bytes.length != 32) {
                throw new IllegalArgumentException();
            }
            key = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Calendar 암호화 키는 32바이트의 Base64 값이어야 합니다.");
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String value, String context) {
        requireConfigured();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer
                    .allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new CalendarException(CalendarFailure.CONFIGURATION_ERROR);
        }
    }

    public String decrypt(String value, String context) {
        requireConfigured();
        try {
            if (!value.startsWith("v1:")) {
                throw new IllegalArgumentException();
            }
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(value.substring(3)));
            if (buffer.remaining() < 28) {
                throw new IllegalArgumentException();
            }
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, context).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new CalendarException(CalendarFailure.CONFIGURATION_ERROR);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String context) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        // 다른 회원 또는 다른 종류의 토큰으로 암호문을 바꿔치기하지 못하게 하는 추가 인증 데이터
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new ApiException(ErrorCode.CALENDAR_NOT_CONFIGURED);
        }
    }
}
