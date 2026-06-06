/*
 * Copyright 2026 Daniel Abbati and the loxmq contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.quaddan.iot.loxmq.miniserver.crypto;

/**
 * Response payload of {@code jdev/sys/getkey2/{user}}.
 * <p>
 * The miniserver answers with the salt + hash-key pair that the binding
 * must use to compute the {@code hash} parameter of the subsequent
 * {@code jdev/sys/getjwt/...} call. The {@code hashAlg} field tells the
 * binding WHICH algorithm to use ({@code "SHA1"} or {@code "SHA256"});
 * the firmware can answer either depending on the user's password setup.
 *
 * <p>Example JSON (string-encoded inside {@code LL.value} per the
 * Loxone single-quote JSON quirk — handled by the parser):
 *
 * <pre>{@code
 * { "key":   "<hex-encoded HMAC key>",
 *   "salt":  "<hex-encoded user salt>",
 *   "hashAlg": "SHA256" }   // or "SHA1" on older firmware setups
 * }</pre>
 *
 * @param key      hex-encoded HMAC key (miniserver-issued, per-session)
 * @param salt     hex-encoded user salt (miniserver-issued, per-session)
 * @param hashAlg  algorithm name advertised by the miniserver
 *                 ({@code "SHA1"} or {@code "SHA256"}); may be {@code null}
 *                 on very old firmware — callers fall back to the
 *                 {@code loxone.miniserver.crypto.hash-password.algo}
 *                 default.
 */
public record KeyAndSalt(String key, String salt, String hashAlg)
{
}
