// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.annotation.PostConstruct;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Encrypts and decrypts the parameters carried by the public
 * {@code /tracking-pixel} URL using authenticated encryption (AES-GCM), so the
 * endpoint can stay open to email clients while every open event remains
 * private and trustworthy.
 *
 * <p>The tracking-pixel endpoint is intentionally reachable anonymously (email
 * clients call it). Packing all of its parameters into a single AES-GCM token
 * keyed by a server-only secret gives that open endpoint two strong guarantees
 * at once. Confidentiality: only this service can read the token, so the
 * recipient's email address (PII) and the campaign/content metadata stay out of
 * access logs, intermediary proxies/CDNs, and Referer headers. Authenticity:
 * only this service can mint a token, so every accepted "email opened" event is
 * genuinely tied to a real send -- a fabricated or altered token simply fails to
 * decrypt and is rejected.</p>
 *
 * <p>Encryption is enabled whenever {@code tracking.pixel.secret} is configured.
 * When it is absent the service fails closed: {@link #decrypt} cannot run and the
 * endpoint rejects every event (a startup error is logged), so a deployment that
 * forgets the secret stays trustworthy by default rather than silently accepting
 * unauthenticated events. Setting {@code tracking.pixel.allow-unsigned=true}
 * deliberately opts back in to cleartext events and is intended for local
 * development only.</p>
 *
 * <p>A freshness guarantee bounds how long any single pixel URL stays usable: an
 * issued-at timestamp is encrypted into the token and {@link #isFresh} accepts
 * only events within {@code tracking.pixel.max-age-days}. Because the timestamp
 * lives inside the authenticated ciphertext it is tamper-proof, so a captured URL
 * cannot be re-dated to extend its life.</p>
 *
 * <p>The AES key is derived from {@code tracking.pixel.secret} with a single
 * SHA-256 pass. Because this secret is a server-only machine credential rather
 * than a human-chosen password, its strength comes from entropy: a
 * cryptographically random 256-bit secret leaves nothing to guess, so even an
 * attacker who intercepts a token and tries to verify guesses offline against the
 * GCM tag has no dictionary to work from. (A slow KDF such as PBKDF2 or Argon2
 * earns its keep only against low-entropy human inputs, and would also need a
 * stored salt that this deterministic, replica-shared derivation has nowhere to
 * keep -- so high entropy is both simpler and the right foundation here.)
 * {@code init()} therefore nudges the operator toward a generated value when the
 * configured secret is short enough to look typed.</p>
 *
 * @author Neil Griffin
 */
@Service
public class TrackingTokenService {

    @PostConstruct
    public void init() {
        _maxAge = (_maxAgeDays > 0) ? Duration.ofDays(_maxAgeDays) : Duration.ZERO;

        if (isEnabled()) {

            // The secret is a server-only machine credential, not a
            // human-chosen password, so high entropy -- not a slow KDF -- is the
            // right foundation: with a cryptographically random 256-bit secret
            // the single SHA-256 pass below leaves an intercepted token nothing
            // to guess offline. (A slow KDF such as PBKDF2/Argon2 pays off only
            // for low-entropy human inputs and would also need a stored salt that
            // this deterministic, replica-shared derivation has nowhere to keep.)
            // Nudge the operator toward a generated value if the configured
            // secret is short enough to look typed.

            if (_secret.length() < _MIN_SECRET_LENGTH) {
                _log.warn(
                    "tracking.pixel.secret is only " + _secret.length() +
                        " characters. It is the AES key for PUBLIC tracking-" +
                        "pixel URLs, so its strength rests on entropy: use a " +
                        "high-entropy, cryptographically random value of at " +
                        "least " + _MIN_SECRET_LENGTH + " characters (e.g. " +
                        "`openssl rand -base64 32`) to keep an intercepted " +
                        "token safe from offline guessing.");
            }

            try {
                byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(
                    _secret.getBytes(StandardCharsets.UTF_8));

                _secretKeySpec = new SecretKeySpec(keyBytes, "AES");
            }
            catch (Exception exception) {
                throw new IllegalStateException(
                    "Unable to initialize AES key", exception);
            }

            return;
        }

        // No secret configured, so tokens cannot be encrypted or decrypted. The
        // safe default is to drop events. An operator can opt back in to
        // accepting cleartext, unauthenticated events (local dev only) via
        // tracking.pixel.allow-unsigned=true. Either way this is logged loudly.

        if (_allowUnsigned) {
            _log.error(
                "tracking.pixel.secret is not configured but " +
                    "tracking.pixel.allow-unsigned=true; the tracking-pixel " +
                    "endpoint will accept cleartext, unauthenticated analytics " +
                    "events and the recipient email address will appear in the " +
                    "URL. This mode trades away the confidentiality and " +
                    "authenticity guarantees and is intended for local " +
                    "development only. Set TRACKING_PIXEL_SECRET to restore " +
                    "encrypted, tamper-proof tracking.");
        }
        else {
            _log.error(
                "tracking.pixel.secret is not configured; tracking-pixel " +
                    "events will be DROPPED (fail closed). Set " +
                    "TRACKING_PIXEL_SECRET to enable tracking, or " +
                    "tracking.pixel.allow-unsigned=true to accept unsigned " +
                    "events in local development.");
        }
    }

    /**
     * Whether token encryption/decryption is active, i.e. a secret is
     * configured.
     */
    public boolean isEnabled() {
        return (_secret != null) && !_secret.isEmpty();
    }

    /**
     * Whether the endpoint should accept cleartext, unauthenticated events when
     * no secret is configured. False (the default) means fail closed. Intended
     * for local development only.
     */
    public boolean isUnsignedAllowed() {
        return _allowUnsigned;
    }

    /**
     * Encrypts the given fields into a single opaque, URL-safe token suitable for
     * the pixel URL's {@code t} parameter. The token is AES-GCM ciphertext (with
     * a random IV prepended) so its contents are confidential and tamper-evident:
     * no email address or campaign metadata appears in cleartext, and any
     * modification causes {@link #decrypt} to fail.
     */
    public String encrypt(Map<String, String> fields) {
        try {
            byte[] iv = new byte[_GCM_IV_LENGTH];

            _secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(_CIPHER_TRANSFORMATION);

            cipher.init(
                Cipher.ENCRYPT_MODE, _secretKeySpec,
                new GCMParameterSpec(_GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintext = _objectMapper.writeValueAsBytes(fields);

            byte[] ciphertext = cipher.doFinal(plaintext);

            // Prepend the IV so decrypt() can recover it; the IV need not be
            // secret, only unique per token (guaranteed by SecureRandom).

            byte[] token = new byte[iv.length + ciphertext.length];

            System.arraycopy(iv, 0, token, 0, iv.length);
            System.arraycopy(ciphertext, 0, token, iv.length, ciphertext.length);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        }
        catch (Exception exception) {
            throw new IllegalStateException(
                "Unable to encrypt tracking parameters", exception);
        }
    }

    /**
     * Decrypts a token produced by {@link #encrypt} back into its fields, or
     * returns {@code null} if the token is missing, malformed, or fails the
     * AES-GCM authentication check (i.e. forged or tampered). A {@code null}
     * result must be treated by the caller as "reject this event".
     */
    public Map<String, String> decrypt(String token) {
        if (!isEnabled() || (token == null) || token.isEmpty()) {
            return null;
        }

        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);

            if (decoded.length <= _GCM_IV_LENGTH) {
                return null;
            }

            byte[] iv = Arrays.copyOfRange(decoded, 0, _GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(
                decoded, _GCM_IV_LENGTH, decoded.length);

            Cipher cipher = Cipher.getInstance(_CIPHER_TRANSFORMATION);

            cipher.init(
                Cipher.DECRYPT_MODE, _secretKeySpec,
                new GCMParameterSpec(_GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);

            return _objectMapper.readValue(
                plaintext, new TypeReference<Map<String, String>>() {
                });
        }
        catch (Exception exception) {

            // Any failure (bad base64, wrong key, failed GCM tag, bad JSON) means
            // the token is not one we issued; reject rather than trust it.

            if (_log.isWarnEnabled()) {
                _log.warn(
                    "Rejecting tracking token that failed to decrypt: " +
                        exception.getMessage());
            }

            return null;
        }
    }

    /**
     * Returns {@code true} if a pixel issued at {@code issuedAtEpochMillis} is
     * still within the configured {@code tracking.pixel.max-age-days} window,
     * bounding how long a captured pixel URL can be replayed. A non-positive
     * max-age (or a blank/unparseable {@code iat}) disables the check and accepts
     * any age, so the timestamp must always come from the authenticated token
     * (i.e. {@link #decrypt}) rather than a cleartext request parameter.
     */
    public boolean isFresh(String issuedAtEpochMillis) {
        if (_maxAge == null || _maxAge.isZero() || _maxAge.isNegative()) {
            return true;
        }

        if ((issuedAtEpochMillis == null) || issuedAtEpochMillis.isEmpty()) {

            // Encryption is enabled but the token carries no issued-at value.
            // Treat as expired so the freshness guarantee is not silently
            // bypassed.

            return false;
        }

        long issuedAt;

        try {
            issuedAt = Long.parseLong(issuedAtEpochMillis.trim());
        }
        catch (NumberFormatException numberFormatException) {
            return false;
        }

        long ageMillis = System.currentTimeMillis() - issuedAt;

        // Reject far-future timestamps as well as expired ones.

        return (ageMillis >= 0) && (ageMillis <= _maxAge.toMillis());
    }

    /**
     * The current issued-at value to encrypt into a freshly built pixel token,
     * as epoch milliseconds.
     */
    public String newIssuedAt() {
        return Long.toString(System.currentTimeMillis());
    }

    private static final String _CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int _GCM_IV_LENGTH = 12;

    private static final int _GCM_TAG_LENGTH_BITS = 128;

    // Below this length the secret looks like a typed password rather than a
    // generated high-entropy value; `openssl rand -base64 32` yields 44 chars.
    private static final int _MIN_SECRET_LENGTH = 32;

    private static final Log _log = LogFactory.getLog(
        TrackingTokenService.class);

    private static final ObjectMapper _objectMapper = new ObjectMapper();

    private static final SecureRandom _secureRandom = new SecureRandom();

    // Accept cleartext, unauthenticated events when no secret is configured.
    // Defaults to false (fail closed); set true for local development only.
    @Value("${tracking.pixel.allow-unsigned:false}")
    private boolean _allowUnsigned;

    // How long a pixel token remains valid, bounding replay. Resolved into
    // _maxAge at startup. Zero or negative disables the freshness check.
    @Value("${tracking.pixel.max-age-days:90}")
    private long _maxAgeDays;

    // Derived from _maxAgeDays in init().
    private Duration _maxAge;

    @Value("${tracking.pixel.secret:}")
    private String _secret;

    private SecretKeySpec _secretKeySpec;

}
