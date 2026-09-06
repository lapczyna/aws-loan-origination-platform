/**
 * Shared vocabulary for values that cross bounded-context boundaries.
 *
 * <p>These enums exist so producers and consumers agree on the meaning of a
 * value. They are deliberately <em>not</em> used as field types in the event
 * payloads: payloads carry the value as a {@code String} so that a consumer
 * built against an older version of this module does not fail to deserialise an
 * event that introduces a new constant. Consumers map the string to an enum and
 * decide explicitly what to do with a value they do not recognise.
 */
package com.example.los.events.vocabulary;
