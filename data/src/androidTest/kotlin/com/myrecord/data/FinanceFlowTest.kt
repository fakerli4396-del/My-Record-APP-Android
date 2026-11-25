package com.myrecord.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.domain.AccountType
import com.myrecord.domain.EntryKind
import com.myrecord.domain.EventType
import com.myrecord.domain.LoanBasis
import com.myrecord.domain.Owner
import com.myrecord.domain.PositionKind
import com.myrecord.domain.ReportPeriod
import com.myrecord.domain.ReportScope
import com.myrecord.domain.Totals
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "finance-${UUID.randomUUID()}.db"
    private val day = LocalDate.of(2024, 2, 1)
    private val month = YearMonth.from(day)
    private val asset = Position(kind = PositionKind.Asset, owner = Owner.Family, name = "测试房产",
        subtype = "property", startDay = day.toString())
    @get:Rule val migration = MigrationTestHelper(instrumentation, LedgerDatabase::class.java)

    @After fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test fun historicalValuationsDistinguishZeroUnknownAndNegativeNetValue() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val id = ledger.finance.saveAsset(asset, Valuation(0, day.toString(), 80_000_000),
                AssetLoan(0, day.toString(), LoanBasis.Manual, 50_000_000))
            val tomorrow = day.plusDays(1)
            ledger.finance.saveAsset(asset.copy(id = id), Valuation(id, tomorrow.toString(), 80_000_000),
                AssetLoan(id, tomorrow.toString(), LoanBasis.Manual, 85_000_000))
            ledger.finance.saveAsset(asset.copy(id = 0, name = "旧车辆", subtype = "vehicle"),
                Valuation(0, day.toString(), 500_000), AssetLoan(0, day.toString(), LoanBasis.Unknown))
            val snapshot = ledger.snapshot()
            assertEquals(30_000_000L, snapshot.portfolio(Owner.Family, day).property.first().equity)
            val current = snapshot.portfolio(Owner.Family, tomorrow)
            assertEquals(-5_000_000L, current.property.first().equity)
            assertNull(current.property.last().equity)
            assertEquals(1, current.unknownLoans)
            val vehicle = current.property.last().position
            ledger.finance.saveAsset(vehicle, Valuation(vehicle.id, tomorrow.toString(), 500_000),
                AssetLoan(vehicle.id, tomorrow.toString(), LoanBasis.Manual, 0))
            assertEquals(500_000L, ledger.snapshot().portfolio(Owner.Family, tomorrow).property.last().equity)
        }
    }

    @Test fun linkedDebtIsCountedOnceAndConflictsAreRejectedAtomically() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val book = setup(ledger)
            val account = ledger.snapshot().accounts.single().id
            val loan = Position(kind = PositionKind.Borrow, owner = Owner.Family, name = "测试贷款",
                subtype = "loan", startDay = day.toString())
            val loanId = ledger.finance.savePosition(loan, FinanceEvent(positionId = 0, day = day.toString(),
                type = EventType.Origin, principal = 50_000_000, openingOnly = true))
            ledger.finance.saveAsset(asset, Valuation(0, day.toString(), 80_000_000),
                AssetLoan(0, day.toString(), LoanBasis.Linked, loanId = loanId))
            reject {
                ledger.finance.saveAsset(asset.copy(name = "冲突项目"), Valuation(0, day.toString(), 80_000_000),
                    AssetLoan(0, day.toString(), LoanBasis.Linked, loanId = loanId))
            }
            reject {
                ledger.finance.saveAsset(asset.copy(name = "个人项目", owner = Owner.Personal),
                    Valuation(0, day.toString(), 80_000_000), AssetLoan(0, day.toString(), LoanBasis.Linked, loanId = loanId))
            }
            reject { ledger.finance.savePosition(loan.copy(id = loanId, owner = Owner.Personal)) }
            ledger.finance.saveEvent(FinanceEvent(positionId = loanId, day = day.plusDays(1).toString(),
                type = EventType.Repayment, principal = 1_000_000, bookId = book, accountId = account))
            val after = ledger.snapshot().portfolio(Owner.Family, day.plusDays(1))
            assertEquals(49_000_000L, after.liabilities)
            assertEquals(31_000_000L, after.property.single().equity)
            assertEquals(49_000_000L, after.property.single().outstanding)
        }
    }

    @Test fun cashBoardUsesOnlyEligibleAccountsAndKeepsNegativeBalances() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            setup(ledger, 10_000)
            suspend fun add(label: String, type: AccountType, amount: Long, owner: Owner = Owner.Family) =
                ledger.saveAccount(Account(name = label, owner = owner, type = type, opening = amount, openingDay = day.toString()))
            add("储蓄", AccountType.Bank, 20_000)
            add("其他", AccountType.Other, -2_000)
            add("信用", AccountType.Credit, -12_000)
            add("钱包", AccountType.Wallet, 14_000)
            add("投资", AccountType.InvestmentCash, 16_000)
            add("个人现金", AccountType.Cash, 50_000, Owner.Personal)
            val snapshot = ledger.snapshot()
            val board = snapshot.portfolio(Owner.Family, day)
            assertEquals(28_000L, board.cash)
            assertEquals(30_000L, board.positiveCash)
            assertTrue(board.accounts.any { it.account.type.cash && it.cents == -2_000L })
            assertEquals(50_000L, snapshot.portfolio(Owner.Personal, day).cash)
        }
    }

    @Test fun planRetainsYearsAndMarksWhenOwnerChangesWithoutChangingActualBills() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val book = setup(ledger)
            val account = ledger.snapshot().accounts.single().id
            val loan = Position(kind = PositionKind.Lend, owner = Owner.Family, name = "测试借出",
                subtype = "receivable", startDay = day.toString())
            val id = ledger.finance.savePosition(loan, FinanceEvent(positionId = 0, day = day.toString(),
                type = EventType.Origin, principal = 30_000, bookId = book, accountId = account))
            val holder = MonthlyPlan.position(id)
            ledger.finance.savePlan(holder, 2024, (1L..12L).map { it * 100 }, "3.5")
            ledger.finance.savePlan(holder, 2025, List(12) { 200L }, "")
            val initial = ledger.snapshot().bills
            ledger.finance.markPlan(holder, 2024, 2)
            ledger.finance.savePosition(loan.copy(id = id, owner = Owner.Personal))
            val after = ledger.snapshot()
            assertEquals(initial, after.bills)
            assertEquals(listOf(7_800L, 2_400L), after.monthlyPlans.map(MonthlyPlan::annual))
            assertTrue(after.monthlyPlans.first().isMarked(2))
            assertEquals(30_000L, after.portfolio(Owner.Personal, day).positionValues.getValue(id).principal)
            assertEquals(0, after.portfolio(Owner.Family, day).positionValues.size)
        }
    }

    @Test fun repaymentPrincipalAndInvestmentCostStayOutOfReportsAndBudget() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val book = setup(ledger)
            val account = ledger.snapshot().accounts.single().id
            val loan = ledger.finance.savePosition(Position(kind = PositionKind.Borrow, owner = Owner.Family,
                name = "借入测试", subtype = "loan", startDay = day.toString()), FinanceEvent(positionId = 0, day = day.toString(),
                type = EventType.Origin, principal = 500_000, bookId = book, accountId = account))
            ledger.finance.saveBudget(book, month, 10_000)
            val before = ledger.snapshot().balance(ledger.snapshot().accounts.single(), day)
            val repayment = ledger.finance.saveEvent(FinanceEvent(positionId = loan, day = day.toString(),
                type = EventType.Repayment, principal = 50_000, extra = 2_000, bookId = book, accountId = account))
            val after = ledger.snapshot()
            assertEquals(before - 52_000, after.balance(after.accounts.single(), day))
            assertEquals(450_000L, after.portfolio(Owner.Family, day).positionValues.getValue(loan).principal)
            assertEquals(Totals(0, 2_000), after.bills.billTotals())
            assertEquals(BudgetStatus(10_000, 2_000), after.budgetStatus(book, month))
            assertEquals(2, after.bills.count { it.sourceEventId == repayment })
            val investment = ledger.finance.savePosition(Position(kind = PositionKind.Investment, owner = Owner.Family,
                name = "投资测试", subtype = "fund", startDay = day.toString()))
            suspend fun event(type: EventType, amount: Long, extra: Long = 0) = ledger.finance.saveEvent(
                FinanceEvent(positionId = investment, day = day.toString(), type = type, principal = amount,
                    extra = extra, bookId = book, accountId = account)
            )
            event(EventType.Purchase, 100_000)
            event(EventType.Valuation, 120_000)
            val estimated = ledger.snapshot()
            assertEquals(20_000L, estimated.portfolio(Owner.Family, day).positionValues.getValue(investment).unrealized)
            assertEquals(0L, estimated.bills.billTotals().income)
            val redeem = event(EventType.Redemption, 100_000, 120_000)
            val redeemed = ledger.snapshot()
            val holding = redeemed.portfolio(Owner.Family, day).positionValues.getValue(investment)
            assertEquals(0L, holding.principal)
            assertEquals(0L, holding.market)
            assertEquals(120_000L, redeemed.balance(redeemed.accounts.single(), day) - estimated.balance(estimated.accounts.single(), day))
            assertEquals(20_000L, redeemed.bills.billTotals().income)
            assertNotNull(redeemed.bills.single { it.sourceEventId == redeem && it.kind == EntryKind.Income }.categoryId)
        }
    }

    @Test fun reportTracksBookCategoryPeriodsAndZeroBucketsAfterCategoryDisabled() = runBlocking {
        LedgerRepository.open(context, name).use { ledger ->
            val family = setup(ledger)
            val second = ledger.saveBook(Book(name = "另一本测试账本"))
            val account = ledger.snapshot().accounts.single().id
            val root = ledger.saveCategory(Category(name = "维修测试", kind = EntryKind.Expense))
            val child = ledger.saveCategory(Category(name = "门窗维修", kind = EntryKind.Expense, parentId = root))
            for ((book, amount) in listOf(family to "200.35", second to "30.10")) {
                val draft = ledger.records.start(book, day.plusDays(5)).draft.copy(amountText = amount,
                    accountId = account, categoryId = child)
                ledger.records.save(draft)
            }
            ledger.saveCategory(ledger.snapshot().categories.first { it.id == child }.copy(enabled = false))
            ledger.finance.saveBudget(family, month, 30_000)
            val snapshot = ledger.snapshot()
            val period = ReportPeriod.of(ReportScope.Month, day)
            val familyReport = snapshot.statistics(period, family, EntryKind.Expense)
            val all = snapshot.statistics(ReportPeriod.of(ReportScope.Year, day), null, EntryKind.Expense)
            val category = snapshot.statistics(ReportPeriod.of(ReportScope.Week, day.plusDays(5)), family, EntryKind.Expense, child)
            assertEquals(20_035L, familyReport.totals.expense)
            assertEquals(20_035L, familyReport.trend[5].cents)
            assertEquals(0L, familyReport.trend.first().cents)
            assertEquals(29, familyReport.trend.size)
            assertEquals(23_045L, all.trend[1].cents)
            assertEquals(CategoryRank(child, 23_045, 2), all.rank.single())
            assertEquals(20_035L, category.averageBill)
            assertEquals(1, category.selected.size)
            assertEquals(9_965L, snapshot.budgetStatus(family, month).remaining)
            assertEquals("维修测试 / 门窗维修", snapshot.categoryLabel(child))
        }
    }

    @Test fun versionTwoRowsRemainUsableInVersionThree() = runBlocking {
        migration.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO books(id,name,archived) VALUES(7,'迁移测试',0)")
            db.execSQL("INSERT INTO settings(`key`,value) VALUES('current_book','7')")
        }
        LedgerRepository.open(context, name).use { ledger ->
            assertEquals(7L, ledger.snapshot().currentBook?.id)
            ledger.finance.saveBudget(7, month, 12_345)
            assertEquals(12_345L, ledger.snapshot().budgetStatus(7, month).budget)
        }
    }

    private suspend fun setup(ledger: LedgerRepository, opening: Long = 2_000_000) = ledger.createFirstLedger(
        "家庭测试", Account(name = "测试现金", owner = Owner.Family, type = AccountType.Cash,
            opening = opening, openingDay = day.toString())
    )

    private suspend fun reject(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected validation to fail")
        } catch (_: IllegalArgumentException) {
        }
    }
}
