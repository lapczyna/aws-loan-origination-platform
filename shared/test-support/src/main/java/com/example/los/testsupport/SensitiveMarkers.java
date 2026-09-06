package com.example.los.testsupport;

import java.util.List;

/**
 * Synthetic values that must never appear in a log line, an event or an audit
 * record.
 *
 * <p>Each marker is a deliberately distinctive, obviously fake string. Tests feed
 * them through real operations and then assert that none of them reached the
 * logging pipeline. Distinctiveness matters: asserting that a log does not
 * contain "Smith" produces flaky results, while asserting it does not contain
 * {@code ZZTESTFAMILYNAMEZZ} is unambiguous.
 *
 * <p><strong>Every value here is invented.</strong> The email address uses the
 * RFC 2606 reserved {@code example.com} domain, the card number is the standard
 * test PAN that no issuer will ever assign, and the identifiers are nonsense
 * strings. Nothing corresponds to a real person, account or credential.
 *
 * <p>This is a second line of defence, not the first. The first is that the code
 * logs explicit safe summaries and never whole domain objects. Regex-based
 * redaction applied after the fact is the weakest form of this control, because
 * it only removes what somebody thought to write a pattern for.
 */
public final class SensitiveMarkers {

    public static final String GIVEN_NAME = "ZZTESTGIVENNAMEZZ";
    public static final String FAMILY_NAME = "ZZTESTFAMILYNAMEZZ";
    public static final String EMAIL_ADDRESS = "zztestmarkerzz@example.com";
    public static final String DATE_OF_BIRTH = "1987-03-11";
    public static final String PHONE_NUMBER = "+441632960123";
    public static final String NATIONAL_ID = "ZZ-NATIONAL-ID-000000";

    /** The standard test card number; not issuable by any real network. */
    public static final String CARD_NUMBER = "4111111111111111";

    public static final String BEARER_TOKEN = "eyJ0ZXN0IjoiWlpURVNUVE9LRU5aWiJ9.ZZTESTTOKENZZ.ZZSIGZZ";
    public static final String PRESIGNED_URL_MARKER = "X-Amz-Signature=ZZTESTPRESIGNEDZZ";
    public static final String DATABASE_PASSWORD = "ZZTESTDBPASSWORDZZ";

    private static final List<String> ALL = List.of(
            GIVEN_NAME,
            FAMILY_NAME,
            EMAIL_ADDRESS,
            DATE_OF_BIRTH,
            PHONE_NUMBER,
            NATIONAL_ID,
            CARD_NUMBER,
            BEARER_TOKEN,
            PRESIGNED_URL_MARKER,
            DATABASE_PASSWORD);

    private SensitiveMarkers() {}

    /** Every marker, for a single "contains none of" assertion. */
    public static List<String> all() {
        return ALL;
    }
}
