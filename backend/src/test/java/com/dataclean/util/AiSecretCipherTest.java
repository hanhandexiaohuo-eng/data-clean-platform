package com.dataclean.util;

import com.dataclean.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class AiSecretCipherTest {

    private final AiSecretCipher cipher = new AiSecretCipher(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptAndDecrypt_roundTrip() {
        String plain = "sk-test-1234567890abcdef";
        String encrypted = cipher.encrypt(plain);

        assertNotEquals(plain, encrypted);
        assertFalse(encrypted.contains(plain));
        assertTrue(encrypted.startsWith("v1:"));
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime() {
        String plain = "sk-test-1234567890abcdef";
        assertNotEquals(cipher.encrypt(plain), cipher.encrypt(plain));
    }

    @Test
    void decrypt_tamperedCiphertext_fails() {
        String encrypted = cipher.encrypt("sk-test-1234567890abcdef");
        char last = encrypted.charAt(encrypted.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;

        assertThrows(BusinessException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void encrypt_withoutMasterKey_fails() {
        AiSecretCipher unconfigured = new AiSecretCipher("");
        assertFalse(unconfigured.isConfigured());
        BusinessException e = assertThrows(BusinessException.class, () -> unconfigured.encrypt("sk-abc"));
        assertEquals(500, e.getCode());
    }

    @Test
    void mask_hidesMiddlePart() {
        assertEquals("sk-****efgh", AiSecretCipher.mask("sk-test-placeholder-0-abcdefgh"));
        assertEquals("****", AiSecretCipher.mask("short"));
        assertEquals("", AiSecretCipher.mask(null));
    }

    @Test
    void invalidMasterKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> new AiSecretCipher("not-base64-32-bytes"));
        assertThrows(IllegalStateException.class,
                () -> new AiSecretCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
