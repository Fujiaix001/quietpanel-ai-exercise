package com.quietpanel.client;

import java.nio.charset.Charset;

final class TransportAuth {
    static final String TOKEN = "qp7-usb-4d2c8a1f-6be0-49d5-91a3";
    static final byte[] TOKEN_BYTES = TOKEN.getBytes(Charset.forName("UTF-8"));

    private TransportAuth() {
    }
}
