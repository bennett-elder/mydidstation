package com.willingtocode.mydidstation

/**
 * Frozen copies of rules that a database migration applied.
 *
 * Never edit anything in this file, and never call it from anywhere except the migration step
 * it names. A migration is a statement about how data looked at one version, so it has to keep
 * behaving exactly the same way forever. If a live rule later learns something new, a user who
 * upgrades from v10 to a much later version would get different repairs than a user who
 * upgraded at v11, from identical data, and no test would catch it, because the tests run the
 * chain once.
 *
 * What protects that promise is `MigrationsTest`, which pins the behaviour directly rather than
 * through the migration chain. A change here is a bug, not an improvement. The live rules are
 * in `PhoneNumbers` and are free to change.
 */
internal object Migrations {

    /**
     * v11: a short code has no country code in front of it.
     *
     * The API reports a five or six digit sender as a NANP number, so the code `89866` arrived
     * as `189866`, and was stored, shown and replied to as a destination that does not exist.
     * The carrier answered `malformed-invalid-to-number`.
     *
     * A leading `1` can only be the country code here: US and Canadian short codes run
     * 20000-99999 and 200000-999999, so a real one never begins with 1. Anything else is
     * returned as it is, because inventing a repair for a shape this rule does not recognise is
     * worse than leaving it alone.
     */
    fun stripCountryCodeV11(digits: String): String {
        if (!digits.startsWith("1")) return digits
        val rest = digits.drop(1)
        return when {
            rest.length == 10 -> rest
            rest.length in 5..6 && rest.first() in '2'..'9' -> rest
            else -> digits
        }
    }
}
