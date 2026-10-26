package it.keybridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import it.keybridge.data.VaultDatabase
import it.keybridge.data.VaultEntry
import it.keybridge.data.VaultRepository
import it.keybridge.hid.HidController
import it.keybridge.hid.UsbGadgetHidController
import it.keybridge.security.CryptoManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.Locale

private enum class VoiceTarget { TITLE, USERNAME, NOTE, SEARCH, NOTES_SEARCH, EDIT_TITLE, EDIT_USERNAME, EDIT_NOTE }

class MainActivity : FragmentActivity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ITALIAN
            }
        }

        val repository = VaultRepository(
            VaultDatabase.get(applicationContext).vaultDao(),
            CryptoManager()
        )
        val hid: HidController = UsbGadgetHidController()

        setContent {
            val isHidSupported by produceState(initialValue = false) {
                value = hid.isSupported()
            }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    KeyBridgeScreen(
                        repository = repository,
                        isHidSupported = isHidSupported,
                        onSend = { entry ->
                            lifecycleScope.launch {
                                runCatching {
                                    val secret = repository.decryptPassword(entry)
                                    try {
                                        val hidResult = hid.typeItalianLayoutForTesting(secret)
                                        hidResult.fold(
                                            onSuccess = {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Password per “${entry.title}” inviata via USB HID!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onFailure = {
                                                copyToClipboard("Password (${entry.title})", String(secret))
                                            }
                                        )
                                    } finally {
                                        secret.fill('\u0000')
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Errore decifratura: ${e.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onCopyPass = { entry ->
                            lifecycleScope.launch {
                                runCatching {
                                    val secret = repository.decryptPassword(entry)
                                    try {
                                        copyToClipboard("Password (${entry.title})", String(secret))
                                    } finally {
                                        secret.fill('\u0000')
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Errore decifratura: ${e.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onCopyUser = { entry ->
                            lifecycleScope.launch {
                                runCatching {
                                    val user = repository.decryptUsername(entry)
                                    if (!user.isNullOrBlank()) {
                                        copyToClipboard("Username (${entry.title})", user, autoClear = false)
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Nessuno username salvato per questa credenziale",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Errore decifratura: ${e.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        onSpeak = { entry ->
                            lifecycleScope.launch {
                                runCatching {
                                    val secret = repository.decryptPassword(entry)
                                    try {
                                        speakPassword(entry.title, secret)
                                    } finally {
                                        secret.fill('\u0000')
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Errore decifratura: ${e.localizedMessage}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun speakPassword(label: String, secret: CharArray) {
        val rawPassword = String(secret)
        val formattedSpeech = formatPasswordForTts(rawPassword)

        val ttsEngine = tts
        if (ttsEngine == null) {
            Toast.makeText(this, "Text-To-Speech non inizializzato", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Lettura vocale della password per “$label”...", Toast.LENGTH_SHORT).show()
        ttsEngine.speak("Password per $label: $formattedSpeech", TextToSpeech.QUEUE_FLUSH, null, "PASSWORD_TTS")
    }

    private fun formatPasswordForTts(password: String): String {
        val builder = StringBuilder()
        for (char in password) {
            val spokenChar = when {
                char.isUpperCase() -> "$char maiuscola, "
                char.isLowerCase() -> "$char, "
                char.isDigit() -> "numero $char, "
                char == ' ' -> "spazio, "
                char == '!' -> "punto esclamativo, "
                char == '?' -> "punto interrogativo, "
                char == '@' -> "chiocciola, "
                char == '#' -> "cancelletto, "
                char == '$' -> "dollaro, "
                char == '%' -> "percento, "
                char == '&' -> "e commerciale, "
                char == '*' -> "asterisco, "
                char == '-' -> "trattino, "
                char == '_' -> "trattino basso, "
                char == '.' -> "punto, "
                else -> "simbolo $char, "
            }
            builder.append(spokenChar)
        }
        return builder.toString().trimEnd(',', ' ')
    }

    private fun copyToClipboard(label: String, text: String, autoClear: Boolean = true) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)

        if (autoClear) {
            Toast.makeText(
                this,
                "$label copiata negli appunti!\nVerrà cancellata tra 30 secondi.",
                Toast.LENGTH_LONG
            ).show()

            lifecycleScope.launch {
                delay(30_000)
                if (clipboard.primaryClip?.description?.label?.startsWith("Password") == true) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        } else {
            Toast.makeText(this, "$label copiato negli appunti!", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun authenticateBiometric(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val biometricManager = BiometricManager.from(activity)

    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    val canAuth = biometricManager.canAuthenticate(authenticators)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        // Fallback: If biometric/PIN is not configured, allow unlocking directly
        onSuccess()
        return
    }

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Sblocca MyPasswordBridge")
        .setSubtitle("Autenticati con impronta, volto o PIN del telefono per accedere all'archivio cifrato")
        .setAllowedAuthenticators(authenticators)
        .build()

    val biometricPrompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onError("Autenticazione fallita. Riprova.")
            }
        }
    )

    biometricPrompt.authenticate(promptInfo)
}

private fun openPayPalWithAmount(context: Context, amount: String?) {
    val cleanAmount = amount?.trim()?.replace(",", ".")?.filter { it.isDigit() || it == '.' }
    val paypalUrl = if (!cleanAmount.isNullOrBlank() && cleanAmount.toDoubleOrNull() != null && cleanAmount.toDouble() > 0) {
        "https://www.paypal.com/paypalme/ElisaMargarito/$cleanAmount"
    } else {
        "https://www.paypal.com/paypalme/ElisaMargarito"
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(paypalUrl))
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "Impossibile aprire il browser web", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun KeyBridgeScreen(
    repository: VaultRepository,
    isHidSupported: Boolean,
    onSend: (VaultEntry) -> Unit,
    onCopyPass: (VaultEntry) -> Unit,
    onCopyUser: (VaultEntry) -> Unit,
    onSpeak: (VaultEntry) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }

    var isVaultUnlocked by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var notesSearchQuery by remember { mutableStateOf("") }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showCameraScanDialog by remember { mutableStateOf(false) }
    var showPayPalOfferDialog by remember { mutableStateOf(false) }

    var editingEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editUsername by remember { mutableStateOf("") }
    var editPassword by remember { mutableStateOf("") }
    var editNote by remember { mutableStateOf("") }

    // Automatic biometric prompt at app start
    LaunchedEffect(Unit) {
        if (activity != null && !isVaultUnlocked) {
            authenticateBiometric(
                activity = activity,
                onSuccess = { isVaultUnlocked = true },
                onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
            )
        }
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty()) {
            debouncedQuery = ""
        } else {
            delay(400)
            debouncedQuery = searchQuery
        }
    }

    val entries by remember(debouncedQuery) { repository.searchEntries(debouncedQuery) }.collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    var pendingSend by remember { mutableStateOf<VaultEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var activeVoiceTarget by remember { mutableStateOf<VoiceTarget?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val jsonString = repository.exportVaultJson()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(jsonString.toByteArray(StandardCharsets.UTF_8))
                    }
                    Toast.makeText(context, "Vault esportato!", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, "Errore export: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val jsonString = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(StandardCharsets.UTF_8).readText()
                    } ?: throw IllegalArgumentException("Impossibile aprire il file")

                    val count = repository.importVaultJson(jsonString)
                    Toast.makeText(context, "Importate $count credenziali!", Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(context, "Errore import: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                when (activeVoiceTarget) {
                    VoiceTarget.TITLE -> title = spokenText
                    VoiceTarget.USERNAME -> username = spokenText
                    VoiceTarget.NOTE -> note = spokenText
                    VoiceTarget.SEARCH -> searchQuery = spokenText
                    VoiceTarget.NOTES_SEARCH -> notesSearchQuery = spokenText
                    VoiceTarget.EDIT_TITLE -> editTitle = spokenText
                    VoiceTarget.EDIT_USERNAME -> editUsername = spokenText
                    VoiceTarget.EDIT_NOTE -> editNote = spokenText
                    null -> {}
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCameraScanDialog = true
        } else {
            Toast.makeText(context, "Permesso fotocamera necessario", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchVoiceInput(target: VoiceTarget, prompt: String) {
        activeVoiceTarget = target
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ITALIAN.language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        runCatching { speechLauncher.launch(intent) }.onFailure {
            Toast.makeText(context, "Input vocale non disponibile", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Centered App Title with Password Vault Vector Icon
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_password_vault),
                        contentDescription = "MyPasswordBridge Vault Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "MyPasswordBridge",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Archivio locale cifrato",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }

                // Dedicated Section / Card with 2-Row Button Layout & Minimal "?" Icon Button
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Gestione Vault & Offerta", style = MaterialTheme.typography.titleSmall)
                            IconButton(
                                onClick = { showInfoDialog = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = "Guida e Info",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Fila 1: Backup ed Importazione
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { exportLauncher.launch("mypasswordbridge_backup.json") },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                            ) {
                                Text("Esporta Vault", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                            ) {
                                Text("Importa Vault", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // Fila 2: Sicurezza & Offerta PayPal
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isVaultUnlocked) {
                                OutlinedButton(
                                    onClick = { isVaultUnlocked = false },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    Text("Blocca Vault", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            OutlinedButton(
                                onClick = { showPayPalOfferDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)
                            ) {
                                Text("Offerta PayPal", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        if (!isVaultUnlocked) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Archivio Bloccato",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "Archivio Cifrato Bloccato",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Le tue password sono protette da cifratura AES-256 su SQLite. Autenticati con l'impronta digitale, il volto o il PIN del telefono per accedere alle credenziali.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                if (activity != null) {
                                    authenticateBiometric(
                                        activity = activity,
                                        onSuccess = { isVaultUnlocked = true },
                                        onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Sblocca",
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text("Sblocca Archivio", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        } else {
            // UNLOCKED STATE: Add form, Search bar & Credential cards
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Compact Add Credential Form
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Nuova Credenziale", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text("Nome", style = MaterialTheme.typography.labelMedium) },
                                trailingIcon = {
                                    IconButton(onClick = { launchVoiceInput(VoiceTarget.TITLE, "Pronuncia Nome...") }) {
                                        Icon(Icons.Default.Mic, contentDescription = "STT Nome", modifier = Modifier.padding(4.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("Username", style = MaterialTheme.typography.labelMedium) },
                                trailingIcon = {
                                    IconButton(onClick = { launchVoiceInput(VoiceTarget.USERNAME, "Pronuncia Username...") }) {
                                        Icon(Icons.Default.Mic, contentDescription = "STT User", modifier = Modifier.padding(4.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password", style = MaterialTheme.typography.labelMedium) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedTextField(
                                value = note,
                                onValueChange = { note = it },
                                label = { Text("Nota", style = MaterialTheme.typography.labelMedium) },
                                trailingIcon = {
                                    IconButton(onClick = { launchVoiceInput(VoiceTarget.NOTE, "Pronuncia Nota...") }) {
                                        Icon(Icons.Default.Mic, contentDescription = "STT Nota", modifier = Modifier.padding(4.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                            Button(
                                onClick = {
                                    val secret = password.toCharArray()
                                    password = ""
                                    scope.launch {
                                        runCatching { repository.add(title, username.ifBlank { null }, secret, note.ifBlank { null }) }
                                            .onSuccess {
                                                title = ""
                                                username = ""
                                                note = ""
                                                message = "Salvata!"
                                            }
                                            .onFailure {
                                                secret.fill('\u0000')
                                                message = "Errore salvataggio."
                                            }
                                    }
                                },
                                enabled = title.isNotBlank() && password.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                Text("Salva", style = MaterialTheme.typography.labelLarge)
                            }

                            message?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }

                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Credenziali", style = MaterialTheme.typography.titleSmall)
                    }

                    // Compact Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Cerca...", style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Cerca") },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                    if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                        showCameraScanDialog = true
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = "OCR")
                                }
                                IconButton(onClick = { launchVoiceInput(VoiceTarget.SEARCH, "Pronuncia ricerca...") }) {
                                    Icon(Icons.Default.Mic, contentDescription = "STT")
                                }
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        if (debouncedQuery.isBlank()) "Cerca per nome, username o nota per estrarre le credenziali dal DB SQLite..." else "Nessun risultato per “$debouncedQuery”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Title & Info Header
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                    Text("Cifrata su SQLite", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                    entry.note?.let { n ->
                                        Text("Nota: $n", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }

                            // Livello 1: Invia | Copia Pass | Copia User
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { pendingSend = entry },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                                ) {
                                    Text("Invia", style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = { onCopyPass(entry) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                                ) {
                                    Text("Copia Pass", style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = { onCopyUser(entry) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                                ) {
                                    Text("Copia User", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            // Livello 2: Leggi | Modifica | Elimina
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onSpeak(entry) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                                ) {
                                    Text("Leggi", style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val currentPass = repository.decryptPassword(entry)
                                            val currentPassStr = String(currentPass)
                                            currentPass.fill('\u0000')
                                            val currentUserStr = repository.decryptUsername(entry) ?: ""

                                            editTitle = entry.title
                                            editUsername = currentUserStr
                                            editPassword = currentPassStr
                                            editNote = entry.note ?: ""
                                            editingEntry = entry
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                                ) {
                                    Text("Modifica", style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            repository.delete(entry.id)
                                            message = "Eliminata."
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                                ) {
                                    Text("Elimina", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Modifica Credenziale", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { editingEntry = null }) {
                        Icon(Icons.Default.Clear, contentDescription = "Chiudi")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Nome", style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = {
                            IconButton(onClick = { launchVoiceInput(VoiceTarget.EDIT_TITLE, "Pronuncia Nome...") }) {
                                Icon(Icons.Default.Mic, contentDescription = "STT Nome")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = editUsername,
                        onValueChange = { editUsername = it },
                        label = { Text("Username", style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = {
                            IconButton(onClick = { launchVoiceInput(VoiceTarget.EDIT_USERNAME, "Pronuncia Username...") }) {
                                Icon(Icons.Default.Mic, contentDescription = "STT User")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = editPassword,
                        onValueChange = { editPassword = it },
                        label = { Text("Password (lascia vuoto se invariata)", style = MaterialTheme.typography.labelMedium) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("Nota", style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = {
                            IconButton(onClick = { launchVoiceInput(VoiceTarget.EDIT_NOTE, "Pronuncia Nota...") }) {
                                Icon(Icons.Default.Mic, contentDescription = "STT Nota")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val passChars = editPassword.ifBlank { null }?.toCharArray()
                        scope.launch {
                            runCatching {
                                repository.update(
                                    id = entry.id,
                                    title = editTitle,
                                    username = editUsername.ifBlank { null },
                                    password = passChars,
                                    note = editNote.ifBlank { null }
                                )
                            }.onSuccess {
                                Toast.makeText(context, "Credenziale aggiornata!", Toast.LENGTH_SHORT).show()
                                editingEntry = null
                            }.onFailure { e ->
                                Toast.makeText(context, "Errore aggiornamento: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = editTitle.isNotBlank()
                ) {
                    Text("Salva Modifiche", style = MaterialTheme.typography.labelMedium)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEntry = null }) {
                    Text("Annulla", style = MaterialTheme.typography.labelMedium)
                }
            }
        )
    }

    if (showPayPalOfferDialog) {
        PayPalOfferDialog(
            onDismissRequest = { showPayPalOfferDialog = false },
            onConfirm = { amount ->
                openPayPalWithAmount(context, amount)
            }
        )
    }

    if (showCameraScanDialog) {
        CameraScanDialog(
            onDismissRequest = { showCameraScanDialog = false },
            onTextSelected = { selectedText ->
                searchQuery = selectedText
                showCameraScanDialog = false
            }
        )
    }

    if (showInfoDialog) {
        val notesEntries by remember(notesSearchQuery) { repository.observeNotes(notesSearchQuery) }.collectAsState(initial = emptyList())

        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Guida & Note", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showInfoDialog = false }) {
                        Icon(Icons.Default.Clear, contentDescription = "Chiudi")
                    }
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Info App", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (isHidSupported) "• USB HID: Attivo (/dev/hidg0)"
                                    else "• USB HID: Assente (Fallback Copia 30s)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("• SQLite Vault: Tutti i dati sono memorizzati nel DB SQLite cifrato con Room per la massima velocità.", style = MaterialTheme.typography.bodySmall)
                                Text("• Autenticazione Biometrica: Archivio protetto da Impronta/Volto/PIN.", style = MaterialTheme.typography.bodySmall)
                                Text("• Modifica & Copia Username: Puoi modificare Nome, Username, Password e Note ed eseguire la copia diretta dello username.", style = MaterialTheme.typography.bodySmall)
                                Text("• Backup: Esporta ed importa il vault in formato JSON.", style = MaterialTheme.typography.bodySmall)
                                Text("• PayPal: Puoi sostenere lo sviluppo dell'app inviando un'offerta a ElisaMargarito.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = { showPayPalOfferDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    Text("Invia Offerta PayPal (ElisaMargarito)", style = MaterialTheme.typography.labelMedium)
                                }
                                Text("• OCR Fotocamera: Tocca l'immagine o 'Scansiona Inquadratura Ora' per leggere parole.", style = MaterialTheme.typography.bodySmall)
                                Text("• TTS & STT: Pulsante 'Leggi' per dettatura e microfoni nei campi per input vocale.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    item {
                        Text("Note Salvate", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = notesSearchQuery,
                            onValueChange = { notesSearchQuery = it },
                            label = { Text("Cerca note...", style = MaterialTheme.typography.labelMedium) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Cerca note") },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { launchVoiceInput(VoiceTarget.NOTES_SEARCH, "Pronuncia nota...") }) {
                                        Icon(Icons.Default.Mic, contentDescription = "STT Note")
                                    }
                                    if (notesSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { notesSearchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (notesEntries.isEmpty()) {
                        item {
                            Text(
                                if (notesSearchQuery.isBlank()) "Nessuna nota." else "Nessun risultato per “$notesSearchQuery”.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        items(notesEntries, key = { it.id }) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    Text(item.note ?: "", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Chiudi", style = MaterialTheme.typography.labelMedium)
                }
            }
        )
    }

    pendingSend?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingSend = null },
            title = { Text("Conferma Invia", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    if (isHidSupported)
                        "La password di “${entry.title}” verrà digitata via cavo USB."
                    else
                        "USB HID non attivo. La password di “${entry.title}” verrà copiata negli appunti (auto-clear in 30s).",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSend = null
                    onSend(entry)
                }) { Text("Conferma", style = MaterialTheme.typography.labelMedium) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSend = null }) { Text("Annulla", style = MaterialTheme.typography.labelMedium) }
            }
        )
    }
}

@Composable
private fun PayPalOfferDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var customAmount by remember { mutableStateOf("5") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invia Offerta PayPal", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Clear, contentDescription = "Chiudi")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Scegli o inserisci l'importo dell'offerta per sostenere lo sviluppo di MyPasswordBridge (ElisaMargarito):",
                    style = MaterialTheme.typography.bodySmall
                )

                Text("Importi rapidi:", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("2", "5", "10", "20").forEach { amount ->
                        OutlinedButton(
                            onClick = { customAmount = amount },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Text("€$amount", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = customAmount,
                    onValueChange = { customAmount = it },
                    label = { Text("Importo personalizzato (€)", style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(customAmount)
                    onDismissRequest()
                }
            ) {
                Text("Invia €${customAmount.ifBlank { "0" }} su PayPal", style = MaterialTheme.typography.labelMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Annulla", style = MaterialTheme.typography.labelMedium)
            }
        }
    )
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScanDialog(
    onDismissRequest: () -> Unit,
    onTextSelected: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var detectedTexts by remember { mutableStateOf<List<String>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var shouldScan by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }

    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Scansiona Testo", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Default.Clear, contentDescription = "Chiudi")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Punta la fotocamera e tocca l'immagine o premi 'Scansiona Inquadratura Ora':",
                    style = MaterialTheme.typography.bodySmall
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                isScanning = true
                                shouldScan = true
                                scanMessage = "Scansione..."
                            }
                        }
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            ContextCompat.getMainExecutor(ctx)
                                        ) { imageProxy ->
                                            val mediaImage = imageProxy.image
                                            if (mediaImage != null && shouldScan) {
                                                shouldScan = false
                                                val image = InputImage.fromMediaImage(
                                                    mediaImage,
                                                    imageProxy.imageInfo.rotationDegrees
                                                )
                                                recognizer.process(image)
                                                    .addOnSuccessListener { visionText ->
                                                        val lines = visionText.textBlocks.flatMap { block ->
                                                            block.lines.map { it.text.trim() }
                                                        }
                                                        val words = visionText.textBlocks.flatMap { block ->
                                                            block.lines.flatMap { line ->
                                                                line.elements.map { it.text.trim() }
                                                            }
                                                        }
                                                        val results = (lines + words)
                                                            .map { it.trim() }
                                                            .filter { it.isNotBlank() && it.length in 2..40 }
                                                            .distinct()

                                                        detectedTexts = results
                                                        scanMessage = if (results.isEmpty()) "Nessun testo trovato." else null
                                                    }
                                                    .addOnFailureListener { e ->
                                                        scanMessage = "Errore: ${e.localizedMessage}"
                                                    }
                                                    .addOnCompleteListener {
                                                        isScanning = false
                                                        imageProxy.close()
                                                    }
                                            } else {
                                                imageProxy.close()
                                            }
                                        }
                                    }

                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isScanning) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                            modifier = Modifier.align(Alignment.Center).padding(6.dp)
                        ) {
                            Text(
                                "Scansione in corso...",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        isScanning = true
                        shouldScan = true
                        scanMessage = "Scansione..."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Text("Scansiona Inquadratura Ora", style = MaterialTheme.typography.labelMedium)
                }

                scanMessage?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                if (detectedTexts.isNotEmpty()) {
                    Text("Testi rilevati (tocca per inserire):", style = MaterialTheme.typography.labelSmall)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(detectedTexts) { text ->
                            AssistChip(
                                onClick = { onTextSelected(text) },
                                label = { Text(text, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Annulla", style = MaterialTheme.typography.labelMedium)
            }
        }
    )
}
