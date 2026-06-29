// SPDX-License-Identifier: LGPL-2.1-or-later
package com.liferay.campaigns.util;

/**
 * Helpers for safely placing dynamic values into HTML email markup.
 *
 * @author Neil Griffin
 */
public class HtmlUtil {

    /**
     * Escapes the characters that are significant in HTML text and
     * double-quoted attribute values so a dynamic value cannot inject markup.
     * Returns the empty string for {@code null}. Use this for every value
     * interpolated into the email body except content that is itself authored
     * HTML (e.g. a RichText field).
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private HtmlUtil() {
    }

}
