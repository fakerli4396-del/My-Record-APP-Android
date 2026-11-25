package com.myrecord.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "catalog_icons")
data class CatalogIcon(
    @PrimaryKey val ownerKey: String,
    val fileId: String,
    val mime: String,
    val bytes: Long,
    val sha256: String
)

@Entity(tableName = "legacy_records", primaryKeys = ["groupName", "rowKey"])
data class LegacyRecord(val groupName: String, val rowKey: String, val description: String)

internal interface TransferDao {
    @Query("SELECT * FROM settings ORDER BY `key`") suspend fun settings(): List<Setting>
    @Query("SELECT * FROM bill_requests ORDER BY token") suspend fun requests(): List<BillRequest>
    @Query("SELECT * FROM catalog_icons ORDER BY ownerKey") suspend fun icons(): List<CatalogIcon>
    @Query("SELECT * FROM catalog_icons WHERE ownerKey = :key") suspend fun icon(key: String): CatalogIcon?
    @Query("DELETE FROM catalog_icons WHERE ownerKey = :key") suspend fun deleteIcon(key: String)
    @Query("SELECT * FROM legacy_records ORDER BY groupName, rowKey") suspend fun legacyRecords(): List<LegacyRecord>
    @Upsert suspend fun save(icon: CatalogIcon)
    @Upsert suspend fun save(record: LegacyRecord)
}
