package com.example.boardapp

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

// 1. Entity
@Entity(tableName = "items")
data class BoardItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val imageUri: String,
    val audioUri: String,
    val parentId: Long? = null
)

// 2. DAO
@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE (:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId")
    fun getItemsByParent(parentId: Long?): Flow<List<BoardItem>>

    @Query("SELECT * FROM items")
    fun getAllItems(): Flow<List<BoardItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: BoardItem)

    @Delete
    suspend fun deleteItem(item: BoardItem)
}

// 3. Database
@Database(entities = [BoardItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "board_db")
                    .build().also { INSTANCE = it }
            }
        }
    }
}

// 4. Audio Player
class AudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    fun playAudio(uriString: String) {
        player?.release()
        try {
            player = MediaPlayer().apply {
                setDataSource(context, Uri.parse(uriString))
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// 5. Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val dao = db.itemDao()
        val audioPlayer = AudioPlayer(this)

        setContent {
            MaterialTheme {
                MainApp(dao = dao, audioPlayer = audioPlayer, context = this)
            }
        }
    }
}

// رنگ آبی روشن برای پس‌زمینه
val SoftLightBlue = Color(0xFFE3F2FD)

@Composable
fun MainApp(dao: ItemDao, audioPlayer: AudioPlayer, context: Context) {
    var isEditMode by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<BoardItem?>(null) }
    var currentParentId by remember { mutableStateOf<Long?>(null) }
    val backStack = remember { mutableStateListOf<Long?>() }

    val currentItems by dao.getItemsByParent(currentParentId).collectAsState(initial = emptyList())
    val allItems by dao.getAllItems().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SoftLightBlue)
    ) {
        if (isEditMode) {
            EditScreen(
                currentParentId = currentParentId,
                items = currentItems,
                availableParents = allItems,
                editingItem = itemToEdit,
                onSave = { id, title, imgUri, audUri, parentId ->
                    coroutineScope.launch {
                        dao.insertItem(
                            BoardItem(
                                id = id ?: 0,
                                title = title,
                                imageUri = imgUri,
                                audioUri = audUri,
                                parentId = parentId
                            )
                        )
                        itemToEdit = null
                    }
                },
                onDelete = { item ->
                    coroutineScope.launch {
                        dao.deleteItem(item)
                        if (itemToEdit?.id == item.id) {
                            itemToEdit = null
                        }
                    }
                },
                onSelectToEdit = { item ->
                    itemToEdit = item
                },
                onCancelEditItem = {
                    itemToEdit = null
                },
                onExitEditMode = {
                    itemToEdit = null
                    isEditMode = false
                },
                onPersistUri = { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                }
            )
        } else {
            DisplayScreen(
                items = currentItems,
                allItems = allItems,
                canGoBack = backStack.isNotEmpty(),
                audioPlayer = audioPlayer,
                onNavigateToChild = { childId ->
                    backStack.add(currentParentId)
                    currentParentId = childId
                },
                onBack = {
                    if (backStack.isNotEmpty()) {
                        currentParentId = backStack.removeAt(backStack.size - 1)
                    }
                },
                onUnlockEditMode = {
                    isEditMode = true
                }
            )
        }
    }
}

// --- حالت نمایش ---
@Composable
fun DisplayScreen(
    items: List<BoardItem>,
    allItems: List<BoardItem>,
    canGoBack: Boolean,
    audioPlayer: AudioPlayer,
    onNavigateToChild: (Long) -> Unit,
    onBack: () -> Unit,
    onUnlockEditMode: () -> Unit
) {
    val context = LocalContext.current
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        if (canGoBack) {
            Button(
                onClick = onBack,
                modifier = Modifier.padding(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Text("بازگشت به سطح قبلی", color = Color.White)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // کارت‌های برنامه
            items(items) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (item.audioUri.isNotEmpty()) {
                                audioPlayer.playAudio(item.audioUri)
                            }
                            if (allItems.any { it.parentId == item.id }) {
                                onNavigateToChild(item.id)
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = item.imageUri,
                            contentDescription = item.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF212121)
                        )
                    }
                }
            }

            // کارت ورود به تنظیمات و ویرایش (۳ بار تاچ سریع)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastClickTime < 1000) {
                                clickCount++
                            } else {
                                clickCount = 1
                            }
                            lastClickTime = currentTime

                            if (clickCount == 3) {
                                clickCount = 0
                                onUnlockEditMode()
                            } else {
                                Toast.makeText(
                                    context,
                                    "${3 - clickCount} بار تاچ دیگر برای ورود به ویرایش",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFBBDEFB)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF90CAF9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "تنظیمات",
                                modifier = Modifier.size(64.dp),
                                tint = Color(0xFF0D47A1)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "تنظیمات و ساخت",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )
                    }
                }
            }
        }
    }
}

// --- حالت ساخت، ویرایش و حذف ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    currentParentId: Long?,
    items: List<BoardItem>,
    availableParents: List<BoardItem>,
    editingItem: BoardItem?,
    onSave: (id: Long?, title: String, imgUri: String, audUri: String, parentId: Long?) -> Unit,
    onDelete: (BoardItem) -> Unit,
    onSelectToEdit: (BoardItem) -> Unit,
    onCancelEditItem: () -> Unit,
    onExitEditMode: () -> Unit,
    onPersistUri: (Uri) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<String?>(null) }
    var audioUri by remember { mutableStateOf<String?>(null) }
    var selectedParentId by remember { mutableStateOf(currentParentId) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(editingItem) {
        if (editingItem != null) {
            title = editingItem.title
            imageUri = editingItem.imageUri
            audioUri = editingItem.audioUri
            selectedParentId = editingItem.parentId
        } else {
            title = ""
            imageUri = null
            audioUri = null
            selectedParentId = currentParentId
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            onPersistUri(it)
            imageUri = it.toString()
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            onPersistUri(it)
            audioUri = it.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (editingItem != null) "ویرایش جایگاه" else "افزودن جایگاه جدید",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D47A1)
            )
            Button(
                onClick = onExitEditMode,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
            ) {
                Text("بازگشت به نمایش")
            }
        }

        // فیلدهای فرم
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("عنوان") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { imagePicker.launch(arrayOf("image/*")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (imageUri == null) Color(0xFF1976D2) else Color(0xFF388E3C))
            ) {
                Text(if (imageUri == null) "انتخاب عکس" else "عکس ثبت شد ✓", fontSize = 12.sp)
            }

            Button(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (audioUri == null) Color(0xFF1976D2) else Color(0xFF388E3C))
            ) {
                Text(if (audioUri == null) "انتخاب صوت" else "صوت ثبت شد ✓", fontSize = 12.sp)
            }
        }

        // انتخاب والد
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            val parentName = availableParents.find { it.id == selectedParentId }?.title ?: "بدون مادر (سطح اصلی)"
            OutlinedTextField(
                value = parentName,
                onValueChange = {},
                readOnly = true,
                label = { Text("جایگاه مادر") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("بدون مادر (سطح اصلی)") },
                    onClick = {
                        selectedParentId = null
                        expanded = false
                    }
                )
                availableParents.filter { it.id != editingItem?.id }.forEach { parent ->
                    DropdownMenuItem(
                        text = { Text(parent.title) },
                        onClick = {
                            selectedParentId = parent.id
                            expanded = false
                        }
                    )
                }
            }
        }

        // دکمه‌های ذخیره / انصراف فرم
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (editingItem != null) {
                OutlinedButton(
                    onClick = onCancelEditItem,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("لغو ویرایش")
                }
            }
            Button(
                onClick = {
                    if (title.isNotEmpty() && imageUri != null && audioUri != null) {
                        onSave(editingItem?.id, title, imageUri!!, audioUri!!, selectedParentId)
                        title = ""
                        imageUri = null
                        audioUri = null
                    }
                },
                enabled = title.isNotEmpty() && imageUri != null && audioUri != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Text(if (editingItem != null) "ثبت تغییرات" else "ذخیره آیتم")
            }
        }

        Divider(modifier = Modifier.padding(vertical = 4.dp), color = Color(0xFF90CAF9))

        Text(
            text = "آیتم‌های موجود در این سطح (جهت ویرایش یا حذف):",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0D47A1)
        )

        // لیست آیتم‌ها همراه با دکمه ویرایش و حذف
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = item.imageUri,
                            contentDescription = item.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(onClick = { onSelectToEdit(item) }) {
                                Icon(Icons.Default.Edit, contentDescription = "ویرایش", tint = Color(0xFF1976D2))
                            }
                            IconButton(onClick = { onDelete(item) }) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف", tint = Color(0xFFD32F2F))
                            }
                        }
                    }
                }
            }
        }
    }
}
