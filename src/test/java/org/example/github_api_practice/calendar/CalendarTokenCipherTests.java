package org.example.github_api_practice.calendar;

import org.example.github_api_practice.global.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CalendarTokenCipherTests {
    static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void roundTripUsesRandomNonceAndHidesPlaintext() {
        var cipher = new CalendarTokenCipher(KEY);
        String first = cipher.encrypt("private-token-fixture", "1:access");
        String second = cipher.encrypt("private-token-fixture", "1:access");
        assertThat(first).doesNotContain("private-token-fixture").isNotEqualTo(second);
        assertThat(cipher.decrypt(first, "1:access")).isEqualTo("private-token-fixture");
    }

    @Test
    void rejectsWrongMemberTokenKindAndModifiedCiphertext() {
        var cipher = new CalendarTokenCipher(KEY);
        String encrypted = cipher.encrypt("fixture", "1:access");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "2:access")).isInstanceOf(CalendarException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "1:refresh")).isInstanceOf(CalendarException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:invalid", "1:access")).isInstanceOf(CalendarException.class);
        String changed = encrypted.substring(0, 12) + (encrypted.charAt(12) == 'A' ? 'B' : 'A') + encrypted.substring(13);
        assertThatThrownBy(() -> cipher.decrypt(changed, "1:access")).isInstanceOf(CalendarException.class);
    }

    @Test
    void missingKeyDisablesCalendarWithoutInventingDefaultSecret() {
        var cipher = new CalendarTokenCipher("");
        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("fixture", "1:access")).isInstanceOf(ApiException.class);
    }

    @Test
    void invalidKeyFailsWithSafeMessage() {
        assertThatThrownBy(() -> new CalendarTokenCipher("invalid-secret-fixture"))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("invalid-secret-fixture");
    }
}
