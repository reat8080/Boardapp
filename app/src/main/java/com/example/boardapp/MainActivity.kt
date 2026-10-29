package com.example.boardapp

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.room.*
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Entity(tableName = "items")
data class BoardItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val imageUri: String,
    val audioUri: String,
    val parentId: Long? = null
)

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE (:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId")
    fun getItemsByParent(parentId: Long?): Flow<List<BoardItem>>

    @Query("SELECT * FROM items")
    fun getAllItems(): Flow<List<BoardItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: BoardItem)
}

@Database(entities = [BoardItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "board_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class AudioPlayer(private val context: Context) {
    private var player: MediaPlayer? = null

    fun playAudio(uriString: String) {
        player?.release()
        player = null
        try {
            player = MediaPlayer().apply {
                setDataSource(context, Uri.parse(uriString))
                setOnCompletionListener { mp ->
                    mp.release()
                    if (player === mp) player = null
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            player?.release()
            player = null
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var audioPlayer: AudioPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = AppDatabase.getDatabase(this).itemDao()
        audioPlayer = AudioPlayer(this)

        setContent {
            MaterialTheme {
                MainApp(dao = dao, audioPlayer = audioPlayer, context = this)
            }
        }
    }

    override fun onDestroy() {
        audioPlayer.release()
        super.onDestroy()
    }
}

@Composable
fun MainApp(dao: ItemDao, audioPlayer: AudioPlayer, context: Context) {
    var isEditMode by remember { mutableStateOf(false) }
    var currentParentId by remember { mutableStateOf<Long?>(null) }
    val backStack = remember { mutableStateListOf<Long?>() }

    val currentItems by dao.getItemsByParent(currentParentId).collectAsState(initial = emptyList())
    val allItems by dao.getAllItems().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    if (isEditMode) {
        CreateItemScreen(
            availableParents = allItems,
            onSave = { title, imgUri, audUri, parentId ->
                coroutineScope.launch {
                    dao.insertItem(
                        BoardItem(
                            title = title,
                            imageUri = imgUri,
                            audioUri = audUri,
                            parentId = parentId
                        )
                    )
                    isEditMode = false
                }
            },
            onCancel = { isEditMode = false },
            onPersistUri = { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some document providers do not offer persistable permissions.
                }
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
            onSwitchToEdit = { isEditMode = true }
        )
    }
}

@Composable
fun DisplayScreen(
    items: List<BoardItem>,
    allItems: List<BoardItem>,
    canGoBack: Boolean,
    audioPlayer: AudioPlayer,
    onNavigateToChild: (Long) -> Unit,
    onBack: () -> Unit,
    onSwitchToEdit: () -> Unit
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canGoBack) {
                    Button(onClick = onBack) { Text("بازگشت") }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                Button(onClick = onSwitchToEdit) { Text("حالت ساخت / ویرایش") }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.padding(padding).fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        audioPlayer.playAudio(item.audioUri)
                        if (allItems.any { it.parentId == item.id }) {
                            onNavigateToChild(item.id)
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = item.imageUri,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateItemScreen(
    availableParents: List<BoardItem>,
    onSave: (String, String, String, Long?) -> Unit,
    onCancel: () -> Unit,
    onPersistUri: (Uri) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<String?>(null) }
    var audioUri by remember { mutableStateOf<String?>(null) }
    var selectedParentId by remember { mutableStateOf<Long?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            onPersistUri(it)
            imageUri = it.toString()
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            onPersistUri(it)
            audioUri = it.toString()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("افزودن جایگاه جدید", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("عنوان") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
            Text(if (imageUri == null) "انتخاب عکس" else "عکس انتخاب شد ✓")
        }

        Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
            Text(if (audioUri == null) "انتخاب صوت" else "صوت انتخاب شد ✓")
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            val parentName = availableParents.find { it.id == selectedParentId }?.title
                ?: "بدون مادر (سطح اصلی)"
            OutlinedTextField(
                value = parentName,
                onValueChange = {},
                readOnly = true,
                label = { Text("جایگاه والد") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier.menuAnchor().fillMaxWidth()
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
                availableParents.forEach { parent ->
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

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OutlinedButton(onClick = onCancel) { Text("انصراف") }
            Button(
                onClick = {
                    if (title.isNotBlank() && imageUri != null && audioUri != null) {
                        onSave(title.trim(), imageUri!!, audioUri!!, selectedParentId)
                    }
                },
                enabled = title.isNotBlank() && imageUri != null && audioUri != null
            ) {
                Text("ذخیره")
            }
        }
    }
}
