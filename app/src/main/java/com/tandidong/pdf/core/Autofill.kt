package com.tandidong.pdf.core

/** A piece of personal information the user saves once and reuses on forms. */
data class AutofillKey(val id: String, val label: String, val keywords: List<String>)

/** Matches form fields to saved personal details (Acrobat-style autofill). */
object Autofill {
    // Ordered from most to least specific: "first name" must win over "name".
    val KEYS = listOf(
        AutofillKey("email", "Email", listOf("email", "e-mail", "mail")),
        AutofillKey("phone", "Phone", listOf("phone", "tel", "mobile", "cell")),
        AutofillKey("birth", "Date of birth", listOf("birth", "dob")),
        AutofillKey("first", "First name", listOf("first name", "firstname", "given name", "first")),
        AutofillKey("last", "Last name", listOf("last name", "lastname", "surname", "family name", "last")),
        AutofillKey("company", "Company", listOf("company", "organization", "organisation", "employer", "business")),
        AutofillKey("zip", "ZIP / postal code", listOf("zip", "postal", "postcode", "post code")),
        AutofillKey("city", "City", listOf("city", "town")),
        AutofillKey("state", "State / province", listOf("state", "province", "region")),
        AutofillKey("country", "Country", listOf("country", "nation")),
        AutofillKey("address", "Address", listOf("address", "street", "addr")),
        AutofillKey("name", "Full name", listOf("full name", "fullname", "name", "applicant", "signer")),
    )

    /** Keywords distinctive enough to match inside run-together names like "applicantemail". */
    private val COMPOUND = setOf("email", "phone", "mobile", "firstname", "lastname", "fullname", "surname", "postcode", "address")

    private fun normalize(s: String) = s.lowercase()
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("[_.\\-:/()\\[\\]]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** The saved value for a field described by [label]/[name], or null if nothing fits. */
    fun suggest(label: String, name: String, profile: Map<String, String>): Pair<AutofillKey, String>? {
        // Split camelCase before lowercasing so "firstName" reads as "first name".
        val text = " " + listOf(label, name).joinToString(" ") {
            it.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        }.let(::normalize) + " "
        for (key in KEYS) {
            val value = profile[key.id]?.takeIf { it.isNotBlank() } ?: continue
            if (key.keywords.any { kw -> text.contains(" $kw ") || (kw in COMPOUND && text.contains(kw)) }) return key to value
        }
        return null
    }
}
