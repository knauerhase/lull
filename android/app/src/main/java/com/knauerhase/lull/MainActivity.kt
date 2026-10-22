package com.knauerhase.lull

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.knauerhase.lull.ui.LullColors
import com.knauerhase.lull.ui.LullTheme
import com.knauerhase.lull.ui.RemoteScreen
import com.knauerhase.lull.ui.ScheduleScreen

class MainActivity : ComponentActivity() {
    private val ble by lazy { BleBedJetLink(this) }
    private val demo by lazy { DemoBedJetLink() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LullTheme {
                LullApp(ble = ble, demo = demo)
            }
        }
    }
}

private enum class Tab(val label: String) {
    SCHEDULE("Schedule"),
    REMOTE("Remote"),
}

@Composable
private fun LullApp(ble: BleBedJetLink, demo: DemoBedJetLink) {
    val context = LocalContext.current
    var useDemo by remember { mutableStateOf(false) }
    val link: BedJetLink = if (useDemo) demo else ble
    val state by link.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.REMOTE) }
    val store = remember { ScheduleStore(context) }
    var slots by remember { mutableStateOf(store.load()) }

    BackHandler(enabled = useDemo) { useDemo = false }

    val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) ble.connect()
    }

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    val onConnect: () -> Unit = {
        useDemo = false
        if (hasBluetoothPermission()) ble.connect() else permissionLauncher.launch(permissions)
    }
    val onDemo: () -> Unit = {
        ble.disconnect()
        useDemo = true
    }
    val onSave: (Int, Schedule?) -> Unit = { slot, schedule ->
        slots = slots.toMutableList().also { it[slot] = schedule }
        store.save(slots)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LullColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 16.dp),
    ) {
        Header(
            title = if (tab == Tab.REMOTE) "Remote" else "Schedule",
            state = state,
            demo = useDemo,
            onConnect = onConnect,
            onDisconnect = { if (useDemo) useDemo = false else ble.disconnect() },
            onDemo = onDemo,
        )
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                Tab.REMOTE -> RemoteScreen(link = link, state = state)
                Tab.SCHEDULE -> ScheduleScreen(
                    state = state,
                    slots = slots,
                    onSave = onSave,
                    onStart = { schedule, slot -> link.startSchedule(schedule, slot) },
                )
            }
        }
        TabBar(selected = tab, onSelect = { tab = it })
    }
}

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            Button(
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSelected) Color.White else LullColors.Raised,
                    contentColor = if (isSelected) Color.Black else Color.White,
                ),
            ) { Text(tab.label) }
        }
    }
}

@Composable
private fun Header(
    title: String,
    state: BedJetState,
    demo: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDemo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        when (state.connection) {
            Connection.READY -> if (!demo) OutlinedButton(onClick = onDisconnect) {
                Text(state.generation ?: "Connected")
            }
            Connection.IDLE, Connection.FAILED -> Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = LullColors.Raised),
                ) { Text("Connect") }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onDemo) { Text("Demo") }
            }
            else -> Text(
                text = "Connecting…",
                color = LullColors.Secondary,
                modifier = Modifier.width(120.dp),
            )
        }
    }
    if (demo) {
        Text(
            text = "Demo mode. Press back to leave.",
            color = LullColors.Secondary,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }
}
