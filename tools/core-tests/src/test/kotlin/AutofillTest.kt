import com.tandidong.pdf.core.Autofill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutofillTest {
    private val profile = mapOf(
        "name" to "Ada Lovelace", "first" to "Ada", "last" to "Lovelace", "email" to "ada@example.com",
        "phone" to "+44 20 7946 0000", "zip" to "W1", "city" to "London", "address" to "12 St James's Square",
    )

    private fun s(label: String, name: String = "") = Autofill.suggest(label, name, profile)?.second

    @Test
    fun matchesCommonFieldNames() {
        assertEquals("Ada Lovelace", s("Full name"))
        assertEquals("Ada", s("First Name"))
        assertEquals("Ada", s("", "firstName"))
        assertEquals("Lovelace", s("Surname"))
        assertEquals("ada@example.com", s("E-mail address"))
        assertEquals("+44 20 7946 0000", s("", "applicant_phone"))
        assertEquals("W1", s("Postal code"))
        assertEquals("London", s("City / Town"))
        assertEquals("12 St James's Square", s("Street address"))
        assertEquals("Ada Lovelace", s("Name of applicant"))
    }

    @Test
    fun ignoresUnrelatedOrEmpty() {
        assertNull(s("Amount due"))
        assertNull(s("Signature date"))
        assertNull(Autofill.suggest("Country", "", profile))
        assertNull(Autofill.suggest("Bank statement", "", profile + ("state" to "CA")))
        assertEquals("ada@example.com", s("", "applicantemail"))
    }
}
