package com.example.ordermanagement.infrastructure.projection

import com.example.ordermanagement.api.dto.response.OrderSummaryResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class OrderSummaryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    fun insert(orderId: String, customerId: String, shippingAddress: String, createdAt: Instant) {
        jdbc.update(
            INSERT_SQL,
            MapSqlParameterSource()
                .addValue("orderId", orderId)
                .addValue("customerId", customerId)
                .addValue("shippingAddress", shippingAddress)
                .addValue("createdAt", Timestamp.from(createdAt))
                .addValue("updatedAt", Timestamp.from(createdAt)),
        )
    }

    fun incrementItemCount(orderId: String, updatedAt: Instant) {
        jdbc.update(
            "UPDATE order_summary SET item_count = item_count + 1, updated_at = :u WHERE order_id = :id",
            MapSqlParameterSource().addValue("id", orderId).addValue("u", Timestamp.from(updatedAt)),
        )
    }

    fun decrementItemCount(orderId: String, updatedAt: Instant) {
        jdbc.update(
            "UPDATE order_summary SET item_count = GREATEST(0, item_count - 1), updated_at = :u WHERE order_id = :id",
            MapSqlParameterSource().addValue("id", orderId).addValue("u", Timestamp.from(updatedAt)),
        )
    }

    fun confirmOrder(orderId: String, totalAmount: BigDecimal, workflowId: String, confirmedAt: Instant) {
        jdbc.update(
            """
            UPDATE order_summary
            SET status = 'CONFIRMED', total_amount = :total, workflow_id = :wf,
                confirmed_at = :confirmedAt, updated_at = :confirmedAt
            WHERE order_id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", orderId)
                .addValue("total", totalAmount)
                .addValue("wf", workflowId)
                .addValue("confirmedAt", Timestamp.from(confirmedAt)),
        )
    }

    fun updateStatus(orderId: String, status: String, updatedAt: Instant) {
        jdbc.update(
            "UPDATE order_summary SET status = :status, updated_at = :u WHERE order_id = :id",
            MapSqlParameterSource()
                .addValue("id", orderId)
                .addValue("status", status)
                .addValue("u", Timestamp.from(updatedAt)),
        )
    }

    fun completePayment(orderId: String, paidAt: Instant) {
        jdbc.update(
            """
            UPDATE order_summary
            SET status = 'PAYMENT_COMPLETED', payment_status = 'COMPLETED',
                paid_at = :paidAt, updated_at = :paidAt
            WHERE order_id = :id
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", orderId).addValue("paidAt", Timestamp.from(paidAt)),
        )
    }

    fun failPayment(orderId: String, updatedAt: Instant) {
        jdbc.update(
            "UPDATE order_summary SET payment_status = 'FAILED', updated_at = :u WHERE order_id = :id",
            MapSqlParameterSource().addValue("id", orderId).addValue("u", Timestamp.from(updatedAt)),
        )
    }

    fun refundPayment(orderId: String, updatedAt: Instant) {
        jdbc.update(
            "UPDATE order_summary SET payment_status = 'REFUNDED', updated_at = :u WHERE order_id = :id",
            MapSqlParameterSource().addValue("id", orderId).addValue("u", Timestamp.from(updatedAt)),
        )
    }

    fun createShipment(orderId: String, trackingNumber: String, updatedAt: Instant) {
        jdbc.update(
            """
            UPDATE order_summary
            SET status = 'SHIPPED', shipment_status = 'CREATED',
                tracking_number = :tracking, updated_at = :u
            WHERE order_id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", orderId)
                .addValue("tracking", trackingNumber)
                .addValue("u", Timestamp.from(updatedAt)),
        )
    }

    fun deliverOrder(orderId: String, deliveredAt: Instant) {
        jdbc.update(
            """
            UPDATE order_summary
            SET status = 'DELIVERED', shipment_status = 'DELIVERED',
                delivered_at = :deliveredAt, updated_at = :deliveredAt
            WHERE order_id = :id
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", orderId).addValue("deliveredAt", Timestamp.from(deliveredAt)),
        )
    }

    fun cancelOrder(orderId: String, cancelReason: String, cancelledAt: Instant) {
        jdbc.update(
            """
            UPDATE order_summary
            SET status = 'CANCELLED', cancelled_at = :cancelledAt,
                cancel_reason = :reason, updated_at = :cancelledAt
            WHERE order_id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", orderId)
                .addValue("reason", cancelReason)
                .addValue("cancelledAt", Timestamp.from(cancelledAt)),
        )
    }

    fun findAll(statuses: List<String>?, customerId: String?, pageable: Pageable): Page<OrderSummaryResponse> {
        val params = MapSqlParameterSource()
        val where = buildWhere(statuses, customerId, params)

        params.addValue("limit", pageable.pageSize)
        params.addValue("offset", pageable.offset)

        val selectSql = "$SELECT_COLS FROM order_summary$where ORDER BY created_at DESC LIMIT :limit OFFSET :offset"
        val countSql = "SELECT COUNT(*) FROM order_summary$where"

        val rows = jdbc.query(selectSql, params) { rs, rowNum -> mapRow(rs, rowNum) }
        val total = jdbc.queryForObject(countSql, params, Long::class.java)
        return PageImpl(rows, pageable, total ?: 0L)
    }

    private fun buildWhere(statuses: List<String>?, customerId: String?, params: MapSqlParameterSource): String {
        val where = StringBuilder(" WHERE 1=1")
        if (!statuses.isNullOrEmpty()) {
            where.append(" AND status IN (:statuses)")
            params.addValue("statuses", statuses)
        }
        if (customerId != null) {
            where.append(" AND customer_id = :customerId")
            params.addValue("customerId", customerId)
        }
        return where.toString()
    }

    private fun mapRow(rs: ResultSet, rowNum: Int): OrderSummaryResponse {
        return OrderSummaryResponse(
            rs.getString("order_id"),
            rs.getString("customer_id"),
            rs.getString("status"),
            rs.getString("payment_status"),
            rs.getString("shipment_status"),
            rs.getBigDecimal("total_amount"),
            rs.getInt("item_count"),
            rs.getString("shipping_address"),
            rs.getString("workflow_id"),
            rs.getString("tracking_number"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("confirmed_at")),
            toInstant(rs.getTimestamp("paid_at")),
            toInstant(rs.getTimestamp("delivered_at")),
            toInstant(rs.getTimestamp("cancelled_at")),
            rs.getString("cancel_reason"),
            toInstant(rs.getTimestamp("updated_at")),
        )
    }

    companion object {
        private fun toInstant(ts: Timestamp?): Instant? = ts?.toInstant()

        private const val INSERT_SQL = """
            INSERT INTO order_summary (
                order_id, customer_id, status, payment_status, shipment_status,
                item_count, shipping_address, created_at, updated_at
            ) VALUES (
                :orderId, :customerId, 'DRAFT', 'PENDING', 'NOT_CREATED',
                0, :shippingAddress, :createdAt, :updatedAt
            )
            """

        private const val SELECT_COLS = """
            SELECT order_id, customer_id, status, payment_status, shipment_status,
                   total_amount, item_count, shipping_address, workflow_id, tracking_number,
                   created_at, confirmed_at, paid_at, delivered_at, cancelled_at,
                   cancel_reason, updated_at"""
    }
}
