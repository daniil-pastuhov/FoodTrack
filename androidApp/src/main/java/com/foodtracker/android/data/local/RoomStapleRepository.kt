package com.foodtracker.android.data.local

import com.foodtracker.model.Staple
import com.foodtracker.repository.StapleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomStapleRepository(private val dao: StapleDao) : StapleRepository {

    override fun getStaplesFlow(): Flow<List<Staple>> =
        dao.getAllFlow()
            .map { rows -> rows.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun getAll(): List<Staple> = dao.getAll().map { it.toDomain() }

    override suspend fun upsert(staple: Staple) = dao.upsert(staple.toEntity())

    override suspend fun upsertAll(staples: List<Staple>) = dao.upsertAll(staples.map { it.toEntity() })

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun clearAll() = dao.clearAll()
}
