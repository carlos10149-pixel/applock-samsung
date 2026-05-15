package com.applock1.app.data

import kotlinx.coroutines.flow.Flow

class AppLockRepository(private val dao: AppLockDao) {

    val lockedApps: Flow<List<LockedAppEntity>> = dao.getAllLockedApps()

    suspend fun isLocked(pkg: String) = dao.isLocked(pkg)

    suspend fun getLockedSet(): Set<String> = dao.getLockedPackageNames().toSet()

    suspend fun toggle(pkg: String) {
        if (dao.isLocked(pkg)) dao.delete(pkg) else dao.insert(LockedAppEntity(pkg))
    }
}
