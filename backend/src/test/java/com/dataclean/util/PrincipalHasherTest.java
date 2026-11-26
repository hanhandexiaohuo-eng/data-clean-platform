package com.dataclean.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主体摘要：不可逆、稳定、空主体归并，且不泄露原文。
 */
class PrincipalHasherTest {

    @Test
    void hashIsStableHexAndHidesSubject() {
        PrincipalHasher hasher = new PrincipalHasher("unit-test-pepper");
        String first = hasher.hash("anon:session-123456");
        String second = hasher.hash("anon:session-123456");
        assertEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertFalse(first.contains("session"));
    }

    @Test
    void differentPepperProducesDifferentDigest() {
        String a = new PrincipalHasher("pepper-a").hash("user:1");
        String b = new PrincipalHasher("pepper-b").hash("user:1");
        assertNotEquals(a, b);
    }

    @Test
    void blankSubjectFallsBackToAnonymous() {
        PrincipalHasher hasher = new PrincipalHasher("unit-test-pepper");
        String blank = hasher.hash("");
        assertEquals(hasher.hash("anonymous"), blank);
        assertEquals(hasher.hash(null), blank);
    }

    @Test
    void missingPepperStillProducesDigest() {
        String digest = new PrincipalHasher("").hash("user:1");
        assertNotEquals("unavailable", digest);
        assertEquals(64, digest.length());
    }
}
