package com.grid.app.core.data.repo

import com.grid.app.core.data.db.GridDatabase
import com.grid.app.core.data.db.dao.CategoryUsage
import com.grid.app.core.data.db.entities.CategoryEntity
import com.grid.app.core.model.Category
import com.grid.app.core.model.CategoryKind
import com.grid.app.core.model.PaymentMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(private val db: GridDatabase) {
    private val dao = db.categoryDao()

    fun observeAll(): Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeActive(kind: CategoryKind): Flow<List<Category>> =
        observeAll().map { list -> list.filter { it.kind == kind && !it.archived } }

    fun observePaymentMethods(): Flow<List<PaymentMethod>> =
        db.paymentMethodDao().observeAll().map { list -> list.filter { !it.archived }.map { it.toDomain() } }

    suspend fun get(id: Long): Category? = dao.get(id)?.toDomain()

    suspend fun byIconKey(iconKey: String, kind: CategoryKind): Category? = dao.byIconKey(iconKey, kind)?.toDomain()

    /** Active categories of [kind]: most-used first (per [usage]), then the rest in their configured order. */
    suspend fun orderedByUsage(kind: CategoryKind, usage: List<CategoryUsage>): List<Category> {
        val active = dao.all().filter { it.kind == kind && !it.archived }.map { it.toDomain() }
        val rank = usage.withIndex().associate { (index, u) -> u.categoryId to index }
        return active.sortedWith(compareBy<Category> { rank[it.id] ?: Int.MAX_VALUE }.thenBy { it.position })
    }

    suspend fun add(name: String, iconKey: String, colorKey: String, kind: CategoryKind): Long =
        dao.insert(CategoryEntity(name = name.trim(), iconKey = iconKey, colorKey = colorKey, kind = kind, position = dao.maxPosition(kind) + 1))

    suspend fun update(category: Category) = dao.update(category.toEntity())

    /** Deletes an unused category; archives it if transactions still reference it (history must keep its label). */
    suspend fun remove(category: Category) {
        if (db.transactionDao().countForCategory(category.id) == 0) dao.delete(category.id)
        else dao.update(category.toEntity().copy(archived = true))
    }

    suspend fun reorder(ordered: List<Category>) =
        dao.updateAll(ordered.mapIndexed { index, c -> c.toEntity().copy(position = index) })

    suspend fun setMonthlyLimit(categoryId: Long, limitMinor: Long?) {
        val entity = dao.get(categoryId) ?: return
        dao.update(entity.copy(monthlyLimitMinor = limitMinor?.takeIf { it > 0 }))
    }
}
