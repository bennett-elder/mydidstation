package com.willingtocode.mydidstation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frozen migration rules, pinned directly.
 *
 * `StoreMigrationTest` walks the upgrade chain once, which proves the chain runs but says
 * nothing about a rule being changed in place: edit `stripCountryCodeV11` and the chain still
 * passes on a fresh database, because it applies the edited rule to data written by the edited
 * rule. These cases are the version that shipped, so an edit fails here instead of quietly
 * repairing second users differently from first ones.
 */
class MigrationsTest {

    @Test
    fun `a short code loses the country code the API added`() {
        assertEquals("89866", Migrations.stripCountryCodeV11("189866"))
        assertEquals("898661", Migrations.stripCountryCodeV11("1898661"))
    }

    @Test
    fun `an ordinary NANP number loses its leading one`() {
        assertEquals("8885551234", Migrations.stripCountryCodeV11("18885551234"))
    }

    @Test
    fun `a short code that already has no prefix is left alone`() {
        assertEquals("89866", Migrations.stripCountryCodeV11("89866"))
    }

    @Test
    fun `a six digit code whose remainder starts with one is not treated as a code`() {
        // The allocated ranges say a US or Canadian short code never begins with 1, so a
        // remainder starting with 1 is something else and stripping it would invent a number.
        assertEquals("112345", Migrations.stripCountryCodeV11("112345"))
    }

    @Test
    fun `anything that is not one of the two shapes comes back untouched`() {
        assertEquals("", Migrations.stripCountryCodeV11(""))
        assertEquals("1", Migrations.stripCountryCodeV11("1"))
        assertEquals("123", Migrations.stripCountryCodeV11("123"))
        assertEquals("15551234", Migrations.stripCountryCodeV11("15551234"))
        assertEquals("155512345678901", Migrations.stripCountryCodeV11("155512345678901"))
    }
}
