package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.InventoryItem
import com.example.data.InventoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InventoryViewModel(private val repository: InventoryRepository) : ViewModel() {

    // List of scanned items
    val allItems: StateFlow<List<InventoryItem>> = repository.allItemsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Scanner state (active = camera is running, stopped = showing the placeholder)
    val isScanningActive = MutableStateFlow(false)

    // Auto-save barcodes instantly without showing confirmation dialog
    val isQuickSaveEnabled = MutableStateFlow(false)

    // Current newly-scanned item pending confirmation/edit (null if none)
    val pendingScanItem = MutableStateFlow<InventoryItem?>(null)

    // Current item being edited from the history list (null if none)
    val editingItem = MutableStateFlow<InventoryItem?>(null)

    // Helper to format timestamps nicely
    fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss yyyy/MM/dd", Locale.US)
        return sdf.format(Date(timestamp))
    }

    // Toggle scanners
    fun toggleScanning() {
        isScanningActive.value = !isScanningActive.value
    }

    fun setScanningActive(active: Boolean) {
        isScanningActive.value = active
    }

    fun toggleQuickSave() {
        isQuickSaveEnabled.value = !isQuickSaveEnabled.value
    }

    // Handle barcode detection
    fun onBarcodeScanned(barcode: String) {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isEmpty()) return

        // Check if we should auto-save or prompt
        if (isQuickSaveEnabled.value) {
            // Check if barcode already exists to increment or save fresh
            viewModelScope.launch {
                val existingList = allItems.value
                val existing = existingList.find { it.barcode == cleanBarcode }
                if (existing != null) {
                    // Update quantity if it's numeric, otherwise keep or append
                    val currentQty = existing.quantity.toIntOrNull()
                    val newQty = if (currentQty != null) (currentQty + 1).toString() else "${existing.quantity} + 1"
                    repository.update(existing.copy(quantity = newQty, timestamp = System.currentTimeMillis()))
                } else {
                    repository.insert(
                        InventoryItem(
                            barcode = cleanBarcode,
                            name = "صنف جديد",
                            quantity = "1",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        } else {
            // Postpone and prompt user with dialog to set name & quantity
            // Check if already showing a dialog to avoid spam
            if (pendingScanItem.value == null) {
                pendingScanItem.value = InventoryItem(
                    barcode = cleanBarcode,
                    name = "صنف جديد",
                    quantity = "1",
                    timestamp = System.currentTimeMillis()
                )
            }
        }
    }

    // Save pending scanned item
    fun savePendingItem(name: String, quantity: String) {
        val item = pendingScanItem.value ?: return
        viewModelScope.launch {
            repository.insert(
                item.copy(
                    name = name.ifBlank { "صنف جديد" },
                    quantity = quantity.ifBlank { "1" },
                    timestamp = System.currentTimeMillis()
                )
            )
            pendingScanItem.value = null
        }
    }

    fun dismissPendingItem() {
        pendingScanItem.value = null
    }

    // Editing existing items
    fun startEditing(item: InventoryItem) {
        editingItem.value = item
    }

    fun saveEditedItem(name: String, quantity: String) {
        val item = editingItem.value ?: return
        viewModelScope.launch {
            repository.update(
                item.copy(
                    name = name.ifBlank { "بدون اسم" },
                    quantity = quantity.ifBlank { "1" }
                )
            )
            editingItem.value = null
        }
    }

    fun dismissEditing() {
        editingItem.value = null
    }

    // Delete item
    fun deleteItem(item: InventoryItem) {
        viewModelScope.launch {
            repository.delete(item)
        }
    }

    fun deleteItemById(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }

    // Clear all
    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    // Insert manually
    fun addManualItem(barcode: String, name: String, quantity: String) {
        viewModelScope.launch {
            repository.insert(
                InventoryItem(
                    barcode = barcode.trim().ifBlank { "M-${System.currentTimeMillis() / 1000}" },
                    name = name.trim().ifBlank { "إدخال يدوي" },
                    quantity = quantity.trim().ifBlank { "1" },
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    // Export CSV and trigger Share Sheet
    fun exportToCSV(context: Context) {
        val itemList = allItems.value
        if (itemList.isEmpty()) {
            Toast.makeText(context, "قائمة الجرد فارغة! لا يوجد بيانات لتصديرها.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // Build CSV Content
            val csvBuilder = StringBuilder()
            // Excel-compatible BOM for Arabic letters to display correctly
            csvBuilder.append('\ufeff')
            // CSV Headers
            csvBuilder.append("مُعرف الصنف,الرمز الشريطي,اسم الصنف,الكمية,وقت وتاريخ المسح\n")

            for (item in itemList) {
                // Escape quotes
                val fBarcode = item.barcode.replace("\"", "\"\"")
                val fName = item.name.replace("\"", "\"\"")
                val fQty = item.quantity.replace("\"", "\"\"")
                val fTime = formatTimestamp(item.timestamp)

                csvBuilder.append("${item.id},\"$fBarcode\",\"$fName\",\"$fQty\",\"$fTime\"\n")
            }

            // Save to file in cache
            val fileName = "جرد_المستودع_${SimpleDateFormat("yyyy_MM_dd_HHmmss", Locale.US).format(Date())}.csv"
            val file = File(context.cacheDir, fileName)
            file.writeText(csvBuilder.toString(), Charsets.UTF_8)

            // Share File URI via FileProvider
            val authority = "${context.packageName}.fileprovider"
            val fileUri: Uri = FileProvider.getUriForFile(context, authority, file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "تصدير جرد المستودع")
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "مشاركة ملف الجرد")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            Toast.makeText(context, "تم تصدير ملف الجرد بنجاح!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "حدث خطأ أثناء التصدير: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

class InventoryViewModelFactory(private val repository: InventoryRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InventoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InventoryViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
