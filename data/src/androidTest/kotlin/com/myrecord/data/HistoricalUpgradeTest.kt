package com.myrecord.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myrecord.domain.Owner
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoricalUpgradeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "v3-upgrade-${UUID.randomUUID()}.db"

    @get:Rule val migration = MigrationTestHelper(instrumentation, LedgerDatabase::class.java)

    @After fun cleanup() {
        instrumentation.targetContext.deleteDatabase(name)
    }

    @Test fun versionThreeLedgerKeepsHistoricalValueAndBillImageReferenceInVersionFour() = runBlocking {
        migration.createDatabase(name, 3).use { db ->
            db.execSQL("INSERT INTO books(id,name,archived) VALUES(1,'旧版虚构账本',0)")
            db.execSQL("INSERT INTO accounts(id,name,owner,type,opening,openingDay,creditLimit) " +
                "VALUES(1,'旧版现金','Family','Cash',10000,'2024-01-01',0)")
            db.execSQL("INSERT INTO categories(id,name,kind,icon,enabled) VALUES(3,'旧版支出','Expense','🌿',1)")
            db.execSQL("INSERT INTO bills(id,bookId,day,kind,cents,accountId,categoryId,note) " +
                "VALUES(1,1,'2024-02-29','Expense',2035,1,3,'旧版账单')")
            db.execSQL("INSERT INTO bill_attachments(id,billId,name,mime,bytes,sha256) " +
                "VALUES('12121212-1212-1212-1212-121212121212',1,'旧版凭证.jpg','image/jpeg',123,'test-sha')")
            db.execSQL("INSERT INTO positions(id,kind,owner,name,subtype,startDay,icon) " +
                "VALUES(1,'Asset','Family','旧版房产','property','2024-01-01','🏠')")
            db.execSQL("INSERT INTO valuations(positionId,day,cents,note) VALUES(1,'2024-02-29',80000000,'旧版估值')")
            db.execSQL("INSERT INTO asset_loans(assetId,day,basis,balance) VALUES(1,'2024-02-29','Manual',50000000)")
        }
        LedgerRepository.open(instrumentation.targetContext, name).use { ledger ->
            val snapshot = ledger.snapshot()
            val family = snapshot.portfolio(Owner.Family, LocalDate.of(2024, 2, 29))
            assertEquals(30_000_000L, family.property.single().equity)
            assertEquals(2_035L, snapshot.bills.single().cents)
            assertEquals(1L, snapshot.attachments.single().billId)
            assertEquals("旧版凭证.jpg", snapshot.attachments.single().name)
        }
    }
}
