package com.applock1.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLockDao {
    @Query("SELECT * FROM locked_apps ORDER BY packageName ASC")
    fun getAllLockedApps(): Flow<List<LockedAppEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM locked_apps WHERE packageName = :pkg)")
    suspend fun isLocked(pkg: String): Boolean

    @Query("SELECT packageName FROM locked_apps")
    suspend fun getLockedPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(app: LockedAppEntity)

    @Query("DELETE FROM locked_apps WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}
