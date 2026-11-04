package com.voice2sms.sendlater.domain;

import java.util.Arrays;

/** java.util.Objects helpers without relying on newer APIs. */
final class Objects2 {
    private Objects2() {}

    static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    static int hash(Object... values) {
        return Arrays.hashCode(values);
    }
}
