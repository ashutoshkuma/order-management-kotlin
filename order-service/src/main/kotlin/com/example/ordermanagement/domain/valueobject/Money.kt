package com.example.ordermanagement.domain.valueobject

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * Value Object: Money
 *
 * Money is a classic example of a Domain Value Object.
 * Using BigDecimal instead of double/float is critical for financial calculations
 * to avoid floating-point precision errors.
 *
 * This class is immutable — all operations return a new Money instance.
 * This is enforced by the data class type and the absence of any setters.
 */
data class Money(val amount: BigDecimal, val currency: Currency) {

    init {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw IllegalArgumentException("Money amount cannot be negative: $amount")
        }
    }

    companion object {
        @JvmField
        val ZERO: Money = Money(BigDecimal.ZERO, Currency.getInstance("USD"))

        @JvmStatic
        @JsonCreator
        fun of(
            @JsonProperty("amount") amount: BigDecimal,
            @JsonProperty("currency") currencyCode: String
        ): Money = Money(amount.setScale(2, RoundingMode.HALF_UP), Currency.getInstance(currencyCode))

        @JvmStatic
        fun of(amount: BigDecimal): Money =
            Money(amount.setScale(2, RoundingMode.HALF_UP), Currency.getInstance("USD"))

        @JvmStatic
        fun of(amount: Double): Money = of(BigDecimal.valueOf(amount))
    }

    /** Returns a new Money with the sum. Currency must match. */
    fun add(other: Money): Money {
        assertSameCurrency(other)
        return Money(this.amount.add(other.amount), this.currency)
    }

    /** Returns a new Money with the difference. Currency must match. */
    fun subtract(other: Money): Money {
        assertSameCurrency(other)
        return Money(this.amount.subtract(other.amount), this.currency)
    }

    /** Multiplies by a quantity for line-item calculation */
    fun multiply(quantity: Int): Money =
        Money(this.amount.multiply(BigDecimal.valueOf(quantity.toLong())), this.currency)

    fun isGreaterThan(other: Money): Boolean {
        assertSameCurrency(other)
        return this.amount.compareTo(other.amount) > 0
    }

    fun isZero(): Boolean = this.amount.compareTo(BigDecimal.ZERO) == 0

    private fun assertSameCurrency(other: Money) {
        if (this.currency != other.currency) {
            throw IllegalArgumentException("Currency mismatch: ${this.currency} vs ${other.currency}")
        }
    }

    @get:JsonProperty("currency")
    val currencyCode: String
        get() = currency.currencyCode

    override fun toString(): String = "${currency.symbol}${amount.toPlainString()}"
}
