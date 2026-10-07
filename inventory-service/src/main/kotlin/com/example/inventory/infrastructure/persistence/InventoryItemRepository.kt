package com.example.inventory.infrastructure.persistence

import com.example.inventory.domain.model.InventoryItem
import org.springframework.data.repository.CrudRepository

interface InventoryItemRepository : CrudRepository<InventoryItem, String>
