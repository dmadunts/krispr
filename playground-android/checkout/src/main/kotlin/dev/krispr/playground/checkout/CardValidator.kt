package dev.krispr.playground.checkout

enum class Network { VISA, MASTERCARD, AMEX, UNKNOWN }

object CardValidator {
    fun digits(input: String): String = input.filter { it.isDigit() }

    fun luhn(number: String): Boolean {
        val ds = digits(number)
        if (ds.length < 12) return false
        var sum = 0
        var double = false
        for (i in ds.length - 1 downTo 0) {
            var d = ds[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    fun network(number: String): Network {
        val ds = digits(number)
        return when {
            ds.startsWith("4") -> Network.VISA
            ds.length >= 2 && ds.substring(0, 2).toInt() in 51..55 -> Network.MASTERCARD
            ds.startsWith("34") || ds.startsWith("37") -> Network.AMEX
            else -> Network.UNKNOWN
        }
    }

    fun expiryValid(month: Int, year: Int, nowMonth: Int, nowYear: Int): Boolean {
        if (month !in 1..12) return false
        if (year < nowYear) return false
        return year > nowYear || month >= nowMonth
    }

    fun cvcValid(cvc: String, network: Network): Boolean {
        val expected = if (network == Network.AMEX) 4 else 3
        return cvc.length == expected && cvc.all { it.isDigit() }
    }
}
