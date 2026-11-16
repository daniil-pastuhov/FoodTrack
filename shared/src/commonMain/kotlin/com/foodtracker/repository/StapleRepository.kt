package com.foodtracker.repository

import com.foodtracker.model.Staple
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The person's staples ([Staple]), in no particular order (`ordered` sorts them). */
interface StapleRepository {
    fun getStaplesFlow(): Flow<List<Staple>>
    suspend fun getAll(): List<Staple>
    suspend fun upsert(staple: Staple)
    suspend fun upsertAll(staples: List<Staple>)
    suspend fun delete(id: String)
    suspend fun clearAll()
}

/** Keeps staples in memory only: the default for tests and previews. */
class InMemoryStapleRepository(staples: List<Staple> = emptyList()) : StapleRepository {
    private val state = MutableStateFlow(staples)
    override fun getStaplesFlow(): Flow<List<Staple>> = state.asStateFlow()
    override suspend fun getAll(): List<Staple> = state.value
    override suspend fun upsert(staple: Staple) {
        state.update { list -> list.filterNot { it.id == staple.id } + staple }
    }
    override suspend fun upsertAll(staples: List<Staple>) {
        staples.forEach { upsert(it) }
    }
    override suspend fun delete(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
    }
    override suspend fun clearAll() {
        state.value = emptyList()
    }
}
