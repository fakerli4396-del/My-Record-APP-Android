package com.myrecord.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.myrecord.domain.EventType
import com.myrecord.domain.FinanceMovement
import com.myrecord.domain.LoanBasis
import com.myrecord.domain.Owner
import com.myrecord.domain.PositionKind

@Entity(tableName = "positions", indices = [Index(value = ["owner", "kind", "name"])])
data class Position(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: PositionKind,
    val owner: Owner,
    val name: String,
    val subtype: String,
    val startDay: String,
    val due: String? = null,
    val purchaseDay: String? = null,
    val purchasePrice: Long? = null,
    val icon: String = "🌿"
)

@Entity(
    tableName = "valuations", primaryKeys = ["positionId", "day"],
    foreignKeys = [ForeignKey(Position::class, parentColumns = ["id"], childColumns = ["positionId"], onDelete = ForeignKey.CASCADE)]
)
data class Valuation(val positionId: Long, val day: String, val cents: Long, val note: String = "")

@Entity(
    tableName = "asset_loans", primaryKeys = ["assetId", "day"],
    foreignKeys = [
        ForeignKey(Position::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Position::class, parentColumns = ["id"], childColumns = ["loanId"])
    ], indices = [Index("loanId")]
)
data class AssetLoan(
    val assetId: Long, val day: String, val basis: LoanBasis,
    val balance: Long? = null, val loanId: Long? = null
)

@Entity(
    tableName = "finance_events",
    foreignKeys = [
        ForeignKey(Position::class, parentColumns = ["id"], childColumns = ["positionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Account::class, parentColumns = ["id"], childColumns = ["accountId"]),
        ForeignKey(Book::class, parentColumns = ["id"], childColumns = ["bookId"])
    ], indices = [Index(value = ["positionId", "day"]), Index("accountId"), Index("bookId")]
)
data class FinanceEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val positionId: Long,
    val day: String,
    val type: EventType,
    val principal: Long,
    val extra: Long = 0,
    val bookId: Long? = null,
    val accountId: Long? = null,
    val note: String = "",
    val openingOnly: Boolean = false
) {
    fun movement() = FinanceMovement(type, principal, extra)
}

@Entity(tableName = "monthly_plans", primaryKeys = ["holder", "year"])
data class MonthlyPlan(
    val holder: String,
    val year: Int,
    val amountsText: String,
    val rate: String = "",
    val marked: Int = 0
) {
    val amounts get() = amountsText.split(',').map(String::toLong)
    val annual get() = amounts.fold(0L, Math::addExact)
    fun isMarked(month: Int) = marked and (1 shl (month - 1)) != 0

    companion object {
        fun position(id: Long) = "position:$id"
        fun credit(id: Long) = "credit:$id"
    }
}

@Entity(
    tableName = "budgets", primaryKeys = ["bookId", "month"],
    foreignKeys = [ForeignKey(Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)]
)
data class Budget(val bookId: Long, val month: String, val cents: Long)

internal interface FinanceDao {
    @Query("SELECT * FROM positions ORDER BY id") suspend fun positions(): List<Position>
    @Query("SELECT * FROM positions WHERE id = :id") suspend fun position(id: Long): Position?
    @Query("SELECT * FROM valuations ORDER BY day") suspend fun valuations(): List<Valuation>
    @Query("SELECT * FROM asset_loans ORDER BY day") suspend fun assetLoans(): List<AssetLoan>
    @Query("SELECT * FROM finance_events ORDER BY day, id") suspend fun events(): List<FinanceEvent>
    @Query("SELECT * FROM finance_events WHERE positionId = :id ORDER BY day, id") suspend fun positionEvents(id: Long): List<FinanceEvent>
    @Query("SELECT * FROM monthly_plans ORDER BY holder, year") suspend fun plans(): List<MonthlyPlan>
    @Query("SELECT * FROM budgets ORDER BY month, bookId") suspend fun budgets(): List<Budget>
    @Query("SELECT * FROM bills WHERE sourceEventId = :id ORDER BY id") suspend fun eventBills(id: Long): List<Bill>
    @Query("DELETE FROM finance_events WHERE id = :id") suspend fun deleteEvent(id: Long)

    @Upsert suspend fun save(position: Position): Long
    @Upsert suspend fun save(valuation: Valuation)
    @Upsert suspend fun save(assetLoan: AssetLoan)
    @Upsert suspend fun save(event: FinanceEvent): Long
    @Upsert suspend fun save(monthlyPlan: MonthlyPlan)
    @Upsert suspend fun save(budget: Budget)
}
