package dev.krispr.playground.checkout

data class Address(val name: String, val line1: String, val city: String, val postcode: String, val country: String)

object AddressValidator {
    private val ukPostcode = Regex("^[A-Z]{1,2}[0-9][A-Z0-9]? ?[0-9][A-Z]{2}$")

    fun normalisePostcode(raw: String): String {
        val compact = raw.uppercase().filter { !it.isWhitespace() }
        if (compact.length < 5) return compact
        return compact.dropLast(3) + " " + compact.takeLast(3)
    }

    fun problems(a: Address): List<String> {
        val out = mutableListOf<String>()
        if (a.name.isBlank()) out += "name"
        if (a.line1.trim().length < 3) out += "line1"
        if (a.city.isBlank()) out += "city"
        if (a.country == "GB" && !ukPostcode.matches(normalisePostcode(a.postcode))) out += "postcode"
        if (a.country.length != 2) out += "country"
        return out
    }
}
