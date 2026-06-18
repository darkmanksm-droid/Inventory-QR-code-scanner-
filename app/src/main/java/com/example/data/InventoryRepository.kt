package com.example.data

import kotlinx.coroutines.flow.Flow

class InventoryRepository(private val inventoryDao: InventoryDao) {
    val allItemsFlow: Flow<List<InventoryItem>> = inventoryDao.getAllItemsFlow()

    suspend fun insert(item: InventoryItem): Long {
        return inventoryDao.insertItem(item)
    }

    suspend fun update(item: InventoryItem) {
        inventoryDao.updateItem(item)
    }

    suspend fun delete(item: InventoryItem) {
        inventoryDao.deleteItem(item)
    }

    suspend fun deleteById(id: Int) {
        inventoryDao.deleteItemById(id)
    }

    suspend fun clearAll() {
        inventoryDao.clearAllItems()
    }
}
