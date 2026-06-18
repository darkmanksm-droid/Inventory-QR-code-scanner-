package com.example

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.InventoryDatabase
import com.example.data.InventoryItem
import com.example.data.InventoryRepository
import com.example.ui.BarcodeAnalyzer
import com.example.ui.InventoryViewModel
import com.example.ui.InventoryViewModelFactory
import com.example.ui.theme.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Core local database setup
        val database = InventoryDatabase.getDatabase(applicationContext)
        val repository = InventoryRepository(database.inventoryDao())
        val viewModelFactory = InventoryViewModelFactory(repository)

        setContent {
            MyApplicationTheme {
                // Force RTL Layout to guarantee identical mirroring as the Arabic reference image
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = ScannerBg
                    ) { innerPadding ->
                        InventoryScannerScreen(
                            modifier = Modifier.padding(innerPadding),
                            factory = viewModelFactory
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun InventoryScannerScreen(
    modifier: Modifier = Modifier,
    factory: InventoryViewModelFactory,
    viewModel: InventoryViewModel = viewModel(factory = factory)
) {
    val context = LocalContext.current
    val allItems by viewModel.allItems.collectAsStateWithLifecycle()
    val isScanningActive by viewModel.isScanningActive.collectAsStateWithLifecycle()
    val isQuickSaveEnabled by viewModel.isQuickSaveEnabled.collectAsStateWithLifecycle()
    val pendingScanItem by viewModel.pendingScanItem.collectAsStateWithLifecycle()
    val editingItem by viewModel.editingItem.collectAsStateWithLifecycle()

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showManualAddDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Camera permission handler
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    // Gallery picker launcher for local barcode decoding (مسح من الصور)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val image = InputImage.fromFilePath(context, uri)
                val scanner = BarcodeScanning.getClient()
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            val rawValue = barcodes[0].rawValue
                            if (!rawValue.isNullOrBlank()) {
                                triggerScanFeedback(context)
                                viewModel.onBarcodeScanned(rawValue)
                            } else {
                                Toast.makeText(context, "الرمز الشريطي فارغ في هذه الصورة!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "لم يتم العثور على أي رمز شريطي أو كود QR في الصورة!", Toast.LENGTH_LONG).show()
                        }
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(context, "فشل فك الرموز: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(context, "خطأ في قراءة الصورة: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScannerBg)
    ) {
        // --- 1. TOP HEADER (تصدير - ماسح الجرد - حذف كلي) ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 1.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(0.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Right: Green "تصدير" button
                Button(
                    onClick = { viewModel.exportToCSV(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = ScannerGreen),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(R.string.export),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.export),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Center: Title with QR symbol
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan Icon",
                        tint = ScannerBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }

                // Left: Settings(Wrench) and Trash icons
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Settings/Wrench details
                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFE3EDFD), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "About",
                            tint = ScannerBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (allItems.isNotEmpty()) {
                                showClearConfirmDialog = true
                            } else {
                                Toast.makeText(context, "القائمة فارغة بالفعل!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(ScannerLightRed, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear All",
                            tint = ScannerRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Main content column
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // --- 2. CONTROLLER / CAMERA PREVIEW CARD ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                colors = CardDefaults.cardColors(containerColor = ScannerDarkGrey),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isScanningActive) {
                        if (cameraPermissionState.status.isGranted) {
                            // Render active camera
                            CameraXPreview(
                                modifier = Modifier.fillMaxSize(),
                                viewModel = viewModel
                            )

                            // Animated Red line representing barcode scanning line
                            var goingDown by remember { mutableStateOf(true) }
                            val infiniteTransition = rememberInfiniteTransition(label = "line")
                            val offsetPercent by infiniteTransition.animateFloat(
                                initialValue = 0.1f,
                                targetValue = 0.9f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1500, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "laser"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .height(2.dp)
                                    .background(ScannerRed)
                                    .align(Alignment.TopCenter)
                                    .fillMaxHeight(offsetPercent) // custom percentage
                                    .offset(y = 240.dp * offsetPercent)
                            )
                        } else {
                            // Permission Request placeholder
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Camera,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.camera_permission_required),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.camera_permission_msg),
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { cameraPermissionState.launchPermissionRequest() },
                                    colors = ButtonDefaults.buttonColors(containerColor = ScannerBlue)
                                ) {
                                    Text(text = stringResource(R.string.grant_permission), color = Color.White)
                                }
                            }
                        }
                    } else {
                        // Stopped state placeholder matching the mock UI completely
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = ScannerBlue,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.scanner_stopped),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.press_start_to_scan),
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(containerColor = ScannerBlue),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.scan_from_images),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- 3. MIDDLE STATS BAR (صنف X تم مسح | متوقف) ---
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Right: Scanned Items count
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "صنف ",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${allItems.size} ",
                            color = ScannerBlue,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "تم مسح:",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }

                    // Left: Stopped/Active indicator state
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            color = if (isScanningActive) ScannerGreen else Color.LightGray,
                            shape = CircleShape
                        ) {}
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isScanningActive) stringResource(R.string.status_active) else stringResource(R.string.status_stopped),
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 4. RECENT ITEMS TITLE BAR ---
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "آخر ",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${allItems.take(10).size} ",
                        fontWeight = FontWeight.Bold,
                        color = ScannerBlue,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "صنف",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    // Badge circle
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(ScannerBlue, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${allItems.size}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Add item manually helper
                TextButton(
                    onClick = { showManualAddDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = ScannerBlue)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.manual_add), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // --- 5. SCANNED ITEMS LIST ---
            AnimatedVisibility(
                visible = allItems.isEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Inbox,
                            contentDescription = null,
                            tint = Color.LightGray,
                            modifier = Modifier.size(60.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.no_items_found),
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = allItems.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    itemsIndexed(allItems, key = { _, item -> item.id }) { index, item ->
                        InventoryItemCard(
                            item = item,
                            isFirst = index == 0,
                            viewModel = viewModel,
                            onDelete = { viewModel.deleteItem(item) },
                            onEdit = { viewModel.startEditing(item) }
                        )
                    }
                }
            }
        }

        // --- 6. BOTTOM PERSISTENT START SCANNING BAR ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Quick Auto-Save scan mode toggle settings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AppSettingsAlt,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "حفظ تلقائي سريع (بدون تواصل مسبق)",
                            color = Color.DarkGray,
                            fontSize = 13.sp
                        )
                    }
                    Switch(
                        checked = isQuickSaveEnabled,
                        onCheckedChange = { viewModel.toggleQuickSave() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ScannerBlue
                        )
                    )
                }

                // Primary Blue Bottom Button
                Button(
                    onClick = { viewModel.toggleScanning() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanningActive) ScannerRed else ScannerBlue
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isScanningActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isScanningActive) stringResource(R.string.stop_scanning) else stringResource(R.string.start_scanning),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }

    // --- DIALOGS SECTION ---

    // 1. CLEAR ALL CONFIRMATION DIALOG
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(text = stringResource(R.string.clear_all_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(text = stringResource(R.string.clear_all_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAll()
                        showClearConfirmDialog = false
                        Toast.makeText(context, "تم مسح كافة البيانات!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ScannerRed)
                ) {
                    Text(text = stringResource(R.string.delete), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    // 2. SCAN CONFIRM / SAVE DIALOG (Open after scanning code when QuickSave is OFF)
    pendingScanItem?.let { item ->
        Dialog(onDismissRequest = { viewModel.dismissPendingItem() }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "صنف جديد ممسوح",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScannerBlue
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Barcode preview block
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.QrCode, contentDescription = null, tint = Color.Gray)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.barcode,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    var itemName by remember { mutableStateOf(item.name) }
                    var itemQuantity by remember { mutableStateOf(item.quantity) }

                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { itemName = it },
                        label = { Text(text = stringResource(R.string.item_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScannerBlue,
                            focusedLabelColor = ScannerBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = itemQuantity,
                        onValueChange = { itemQuantity = it },
                        label = { Text(text = stringResource(R.string.item_quantity_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScannerBlue,
                            focusedLabelColor = ScannerBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { viewModel.dismissPendingItem() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(R.string.cancel), color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.savePendingItem(itemName, itemQuantity) },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = ScannerBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = stringResource(R.string.save), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 3. EDIT ITEM DETAILS DIALOG
    editingItem?.let { item ->
        Dialog(onDismissRequest = { viewModel.dismissEditing() }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.edit_item_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScannerBlue
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.QrCode, contentDescription = null, tint = Color.Gray)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.barcode,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    var itemName by remember { mutableStateOf(item.name) }
                    var itemQuantity by remember { mutableStateOf(item.quantity) }

                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { itemName = it },
                        label = { Text(text = stringResource(R.string.item_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScannerBlue,
                            focusedLabelColor = ScannerBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = itemQuantity,
                        onValueChange = { itemQuantity = it },
                        label = { Text(text = stringResource(R.string.item_quantity_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScannerBlue,
                            focusedLabelColor = ScannerBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { viewModel.dismissEditing() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(R.string.cancel), color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { viewModel.saveEditedItem(itemName, itemQuantity) },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = ScannerBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = stringResource(R.string.save), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 4. MANUAL BARCODE ADDITION DIALOG
    if (showManualAddDialog) {
        Dialog(onDismissRequest = { showManualAddDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.manual_add_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ScannerBlue
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    var barcode by remember { mutableStateOf("") }
                    var name by remember { mutableStateOf("") }
                    var quantity by remember { mutableStateOf("1") }

                    OutlinedTextField(
                        value = barcode,
                        onValueChange = { barcode = it },
                        label = { Text(text = stringResource(R.string.barcode_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScannerBlue,
                            focusedLabelColor = ScannerBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(text = stringResource(R.string.item_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScannerBlue,
                            focusedLabelColor = ScannerBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text(text = stringResource(R.string.item_quantity_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ScannerBlue,
                            focusedLabelColor = ScannerBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { showManualAddDialog = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = stringResource(R.string.cancel), color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = {
                                if (barcode.isNotBlank()) {
                                    viewModel.addManualItem(barcode, name, quantity)
                                    showManualAddDialog = false
                                } else {
                                    Toast.makeText(context, "الرجاء إدخال الرمز الشريطي أولاً!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = ScannerBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = stringResource(R.string.save), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 5. ABOUT APP / WORKFLOW GUIDE DIALOG (Launched via Settings Wrench icon)
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = ScannerBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "حول تطبيق الجرد", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(text = "مرحباً بك في تطبيق ماسح الجرد المستودعي دون اتصال بالشبكة.", fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "• هذا التطبيق يعمل بالكامل دون الحاجة للإنترنت بنسبة 100%.", fontSize = 13.sp)
                    Text(text = "• يمكنك مسح الأكواد سريعاً عبر تشغيل الكاميرا من زر 'ابدأ المسح'.", fontSize = 13.sp)
                    Text(text = "• زر 'مسح من الصور' يمكنك من فك تشفير الكود من معرض الصور محلياً.", fontSize = 13.sp)
                    Text(text = "• اضغط وتفاعل مع عنصر الجرد المسجل لتحديث معلوماته أو الكميات.", fontSize = 13.sp)
                    Text(text = "• زر 'تصدير' يتيح لك فوراً إنشاء تقرير Excel/CSV ومشاركته عبر الواتساب أو البريد الإلكتروني.", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "مُصمم بجودة وسلاسة لتلبية خدمات الجرد المحلية الفورية.", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = ScannerBlue, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ScannerBlue)
                ) {
                    Text(text = "حسناً", color = Color.White)
                }
            }
        )
    }
}

// Single list item displaying scanned barcodes
@Composable
fun InventoryItemCard(
    item: InventoryItem,
    isFirst: Boolean,
    viewModel: InventoryViewModel,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        // If it's the newest item (first in list), draw a primary blue outline to match the mock UI
        border = BorderStroke(
            width = if (isFirst) 1.5.dp else 1.dp,
            color = if (isFirst) ScannerBlue else Color(0xFFE2E8F0)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFirst) 2.dp else 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Right: item icon inside light blue square container
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFE8F2FF), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = ScannerBlue,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center: Details
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Barcode + "جديد" green tag for first item
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.barcode,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isFirst) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(ScannerLightGreen, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.badge_new),
                                color = ScannerGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Item Name Description
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    color = Color.DarkGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Metadata row (Quantity + Time)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Folder & Quantity indicator
                    Row(
                        modifier = Modifier
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = ScannerGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.quantity,
                            color = ScannerGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Clock & Time indicator
                    Row(
                        modifier = Modifier
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = viewModel.formatTimestamp(item.timestamp),
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Left: Delete / Trash bin in Red
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .size(36.dp)
                    .background(ScannerLightRed, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = ScannerRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }

    // Single item delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = stringResource(R.string.delete_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(text = stringResource(R.string.delete_confirm_msg)) },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ScannerRed)
                ) {
                    Text(text = stringResource(R.string.delete), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

// CameraX implementation wrapper
@Composable
fun CameraXPreview(
    modifier: Modifier = Modifier,
    viewModel: InventoryViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(key1 = true) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val analyzer = BarcodeAnalyzer { barcode ->
            triggerScanFeedback(context)
            viewModel.onBarcodeScanned(barcode)
        }

        imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (exc: Exception) {
            exc.printStackTrace()
        }
    }

    // Clean up camera resources when element hides
    DisposableEffect(key1 = true) {
        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

// Triggers offline audio and tactile/vibration scan success feedback to make scanning deeply satisfying
fun triggerScanFeedback(context: Context) {
    try {
        // 1. Tactile feedback (Vibrate for 100ms)
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        if (vibrator != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        100,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }

        // 2. Audible notification (Double beep tone)
        val toneG = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 90)
        toneG.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
