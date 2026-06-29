// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.util;

/**
 * Logging helpers for keeping personally identifiable information (PII) out of
 * log output.
 *
 * @author Neil Griffin
 */
public class LogUtil {

    /**
     * Masks an email address for logging, e.g. {@code john.doe@example.com}
     * becomes {@code j***@e***.com}. Returns {@code (none)} for null/blank
     * input. Keeps just enough (first character of the local part, first
     * character and TLD of the domain) for operational traceability without
     * recording the actual address.
     */
    public static String maskEmail(String email) {
        if ((email == null) || email.isEmpty()) {
            return "(none)";
        }

        int at = email.indexOf('@');

        if (at < 1) {

            // No local part, or no "@" -- don't risk echoing it back.

            return "***";
        }

        String local = email.substring(0, at);
        String domain = email.substring(at + 1);

        String maskedLocal = local.charAt(0) + "***";

        int lastDot = domain.lastIndexOf('.');

        String maskedDomain = (lastDot > 0)
            ? domain.charAt(0) + "***" + domain.substring(lastDot)
            : domain.charAt(0) + "***";

        return maskedLocal + "@" + maskedDomain;
    }

    private LogUtil() {
    }

}
