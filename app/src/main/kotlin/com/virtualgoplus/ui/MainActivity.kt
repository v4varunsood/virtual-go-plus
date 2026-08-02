package com.virtualgoplus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.virtualgoplus.engine.AutoCatcherEngine
import com.virtualgoplus.gatt.GattServerManager
import com.virtualgoplus.service.GoPlusBleService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var bleService: GoPlusBleService? = null
    private var isBound by mutableStateOf(false)
    private var connectionState by mutableStateOf<GoPlusBleService.ConnectionState>(
        GoPlusBleService.ConnectionState.DISCONNECTED
    )
    private var eventLog by mutableStateOf<List<AutoCatcherEngine.CatchEvent>>(emptyList())
    private var autoCatchEnabled by mutableStateOf(true)
    private var autoSpinEnabled by mutableStateOf(true)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GoPlusBleService.LocalBinder
            bleService = binder.getService()
            isBound = true
            Log.d(TAG, "Service bound")
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            isBound = false
        }
    }

    private fun observeService() {
        bleService?.let { svc ->
            // Poll connection state
            kotlinx.coroutines.MainScope().launch {
                while (isBound) {
                    connectionState = svc.connectionState
                    delay(500)
                }
            }
        }
    }

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
        } else {
            Log.w(TAG, "Some permissions denied: $results")
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check/request Bluetooth permissions
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }

        // Ensure Bluetooth is enabled
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
        }

        setContent {
            MaterialTheme {
                VirtualGoPlusUI(
                    connectionState = connectionState,
                    eventLog = eventLog,
                    autoCatchEnabled = autoCatchEnabled,
                    autoSpinEnabled = autoSpinEnabled,
                    onStartService = { startBleService() },
                    onStopService = { stopBleService() },
                    onAutoCatchToggle = { autoCatchEnabled = it },
                    onAutoSpinToggle = { autoSpinEnabled = it }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, GoPlusBleService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleService() {
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
            return
        }
        Intent(this, GoPlusBleService::class.java).apply {
            action = "START"
        }.also {
            startForegroundService(it)
        }
        Intent(this, GoPlusBleService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopBleService() {
        Intent(this, GoPlusBleService::class.java).apply {
            action = "STOP"
        }.also {
            startService(it)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
fun VirtualGoPlusUI(
    connectionState: GoPlusBleService.ConnectionState,
    eventLog: List<AutoCatcherEngine.CatchEvent>,
    autoCatchEnabled: Boolean,
    autoSpinEnabled: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onAutoCatchToggle: (Boolean) -> Unit,
    onAutoSpinToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Title
        Text(
            text = "Virtual GO Plus",
            fontSize = 24.sp,
            fontFamily = FontFamily.Default,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Connection state badge
        val stateText = when (connectionState) {
            GoPlusBleService.ConnectionState.ADVERTISING -> "Advertising"
            GoPlusBleService.ConnectionState.CONNECTED -> "Connected"
            GoPlusBleService.ConnectionState.DISCONNECTED -> "Disconnected"
            GoPlusBleService.ConnectionState.PAIRING -> "Pairing..."
        }
        val stateColor = when (connectionState) {
            GoPlusBleService.ConnectionState.ADVERTISING -> androidx.compose.ui.graphics.Color(0xFFFFA000)
            GoPlusBleService.ConnectionState.CONNECTED -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
            GoPlusBleService.ConnectionState.DISCONNECTED -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
            GoPlusBleService.ConnectionState.PAIRING -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        }

        Surface(
            color = stateColor.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stateText,
                color = stateColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Service toggle button
        Button(
            onClick = {
                when (connectionState) {
                    GoPlusBleService.ConnectionState.DISCONNECTED -> onStartService()
                    else -> onStopService()
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = when (connectionState) {
                    GoPlusBleService.ConnectionState.DISCONNECTED -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                    else -> androidx.compose.ui.graphics.Color(0xFFF44336)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = when (connectionState) {
                    GoPlusBleService.ConnectionState.DISCONNECTED -> "Start BLE Service"
                    else -> "Stop BLE Service"
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Auto toggle switches
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto-Catch Pokémon", fontSize = 16.sp)
            Switch(
                checked = autoCatchEnabled,
                onCheckedChange = onAutoCatchToggle
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto-Spin PokéStops", fontSize = 16.sp)
            Switch(
                checked = autoSpinEnabled,
                onCheckedChange = onAutoSpinToggle
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Event log
        Text(
            text = "Event Log",
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (eventLog.isEmpty()) {
            Text(
                text = "No events yet",
                color = androidx.compose.ui.graphics.Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
            ) {
                items(eventLog.take(50)) { event ->
                    EventLogItem(event)
                }
            }
        }
    }
}

@Composable
fun EventLogItem(event: AutoCatcherEngine.CatchEvent) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = timeFormat.format(Date(event.timestamp))

    val color = when (event.type) {
        AutoCatcherEngine.EventType.POKEMON_ENCOUNTER -> androidx.compose.ui.graphics.Color(0xFF2196F3)
        AutoCatcherEngine.EventType.POKESTOP_ENCOUNTER -> androidx.compose.ui.graphics.Color(0xFF9C27B0)
        AutoCatcherEngine.EventType.AUTO_CATCH_TRIGGERED,
        AutoCatcherEngine.EventType.AUTO_SPIN_TRIGGERED -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        AutoCatcherEngine.EventType.BUTTON_PRESS,
        AutoCatcherEngine.EventType.BUTTON_RELEASE -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        AutoCatcherEngine.EventType.DISMISSED -> androidx.compose.ui.graphics.Color(0xFFF44336)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = timeStr,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = androidx.compose.ui.graphics.Color.Gray,
            modifier = Modifier.width(70.dp)
        )
        Text(
            text = "[${event.type.name}]",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = event.details,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
