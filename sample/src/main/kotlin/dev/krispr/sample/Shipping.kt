package dev.krispr.sample

data class Address(val city: String?, val postcode: String?)

class Customer(val name: String, val address: Address?)

object Shipping {
    fun cityLabel(customer: Customer?): String = customer?.address?.city?.uppercase() ?: "UNKNOWN"

    fun isLocal(customer: Customer?): Boolean = customer?.address?.postcode?.startsWith("2000") == true

    fun shippingCents(customer: Customer?, weightGrams: Int): Int {
        if (isLocal(customer)) return 500
        return 900 + weightGrams / 100 * 50
    }

    /** Never called by a test: every mutant here is NO_COVERAGE. The println argument is never mutated. */
    fun describe(customer: Customer?, weightGrams: Int): String {
        println("describing ${weightGrams * 2}")
        return if (weightGrams > 1000) "heavy parcel to ${cityLabel(customer)}" else "parcel to ${cityLabel(customer)}"
    }
}
