package com.knauerhase.lull

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.knauerhase.lull.bedjet.Mode
import com.knauerhase.lull.bedjet.V2
import com.knauerhase.lull.bedjet.V2Info
import com.knauerhase.lull.bedjet.V2Status
import com.knauerhase.lull.bedjet.V3
import com.knauerhase.lull.bedjet.V3Step
import com.knauerhase.lull.bedjet.maxMinutes
import com.knauerhase.lull.bedjet.maxMinutes
import com.knauerhase.lull.bedjet.temperatureRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.util.UUID

/**
 * Talks to a real BedJet over Bluetooth LE. Mirrors `BLELink.swift` in the Lull iOS app.
 *
 * Not yet tested against hardware. The protocol bytes come from the codec module, which is tested;
 * the GATT plumbing is not. Missing so far: remembering the unit, the hand-over to another phone,
 * and BedJet 3 program storage.
 */
@SuppressLint("MissingPermission")
class BleBedJetLink(context: Context) : BedJetLink {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _state = MutableStateFlow(BedJetState())
    override val state: StateFlow<BedJetState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var isV2 = false
    private var v2Status: V2Status? = null
    private var uartBuffer = ByteArray(0)
    private var v2Info: V2Info? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Bluetooth callbacks arrive on a binder thread unless a handler is given, so they run on the main thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Android allows one GATT operation at a time, so writes and descriptor setup go through this queue.
    private val operations = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false

    // MARK: Connection

    override fun connect() {
        val connection = _state.value.connection
        if (connection == Connection.SCANNING || connection == Connection.CONNECTING || connection == Connection.READY) return
        val scanner = adapter.bluetoothLeScanner
        if (!adapter.isEnabled || scanner == null) {
            fail("Turn on Bluetooth to find your BedJet.")
            return
        }
        _state.update { BedJetState(connection = Connection.SCANNING) }
        scanner.startScan(scanCallback)
    }

    override fun disconnect() {
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        cleanup()
        _state.update { BedJetState() }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            // The V2 is recognised by its name, as in the iOS app.
            if (!name.uppercase().startsWith("BEDJET")) return
            adapter.bluetoothLeScanner?.stopScan(this)
            _state.update { it.copy(connection = Connection.CONNECTING) }
            gatt = result.device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                mainHandler,
            )
        }

        override fun onScanFailed(errorCode: Int) {
            fail("Scanning failed (code $errorCode).")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (_state.value.connection != Connection.FAILED) {
                        cleanup()
                        _state.update { BedJetState(note = "Disconnected from your BedJet.") }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("Couldn't read the BedJet's services.")
            val v3 = gatt.getService(V3_SERVICE)
            val uart = gatt.getService(UART_SERVICE)
            if (v3 != null) {
                isV2 = false
                commandCharacteristic = v3.getCharacteristic(V3_COMMAND)
                enableNotifications(v3.getCharacteristic(V3_STATUS) ?: return fail("This BedJet has no status feed."))
            } else if (uart != null) {
                isV2 = true
                commandCharacteristic = uart.getCharacteristic(UART_WRITE)
                enableNotifications(uart.getCharacteristic(UART_NOTIFY) ?: return fail("This BedJet has no status feed."))
            } else {
                fail("That device isn't a BedJet.")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Couldn't turn on the BedJet's status feed.")
                return
            }
            if (descriptor.characteristic == notifyCharacteristic) {
                _state.update { it.copy(connection = Connection.READY, generation = if (isV2) "BedJet V2" else "BedJet 3") }
                // The V2 needs its info request before it reports the rest of its settings.
                if (isV2) enqueue(write(V2.infoRequest()))
            }
            finishOperation()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(note = "The BedJet didn't accept that command.") }
            }
            finishOperation()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(value)
        }

        // Android 12 and 13 call this older form instead of the one above.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            receive(characteristic.value ?: return)
        }
    }

    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val gatt = gatt ?: return
        notifyCharacteristic = characteristic
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG) ?: return fail("The BedJet's status feed can't be turned on.")
        enqueue { g -> writeDescriptor(g, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
    }

    // MARK: Commands

    override fun setMode(mode: Mode) {
        val command = if (isV2) V2.button(mode, _state.value.mode) else V3.setMode(mode)
        command?.let(::send)
        // Switching on without a schedule runs as long as the mode allows, up to 10 hours. The unit's timer is set
        // once it has started, so the change waits a moment for the unit's status to catch up.
        if (command != null && mode != Mode.STANDBY) {
            scope.launch {
                delay(1_200)
                setRuntime(minOf(mode.maxMinutes, MAX_SCHEDULE_MINUTES))
            }
        }
    }

    override fun startSchedule(schedule: Schedule, slot: Int) {
        if (_state.value.connection != Connection.READY) return
        val problem = schedule.problemFor(isV2)
        if (problem != null) {
            _state.update { it.copy(note = problem) }
            return
        }
        if (isV2) startSequence(schedule) else startProgram(schedule, slot)
    }

    /** A BedJet 3 keeps the schedule in one of its program slots and starts that slot. */
    private fun startProgram(schedule: Schedule, slot: Int) {
        val steps = schedule.phases.map {
            V3Step(it.mode, it.fanStep, it.temperature, hours = it.minutes / 60, minutes = it.minutes % 60)
        }
        val now = LocalTime.now()
        scope.launch {
            send(V3.setClock(now.hour, now.minute))
            V3.writeProgram(slot, steps).forEach(::send)
            send(V3.nameProgram(slot, schedule.name))
            delay(300)
            send(V3.startProgram(slot))
        }
    }

    /** A BedJet V2 holds one sequence, written step by step and then run. The official app waits 200 ms between writes. */
    private fun startSequence(schedule: Schedule) {
        val info = v2Info ?: run {
            _state.update { it.copy(note = "Still reading the BedJet's firmware. Try again in a moment.") }
            return
        }
        if (!info.runsSequences) {
            _state.update {
                it.copy(note = "This BedJet V2 needs a firmware update before it can run schedules. Update it in the official BedJet app.")
            }
            return
        }
        val now = LocalTime.now()
        scope.launch {
            send(V2.clearSequence())
            delay(200)
            schedule.phases.forEachIndexed { index, phase ->
                send(V2.writeStep(phase.mode, phase.fanStep, phase.temperature, phase.minutes, index, info.extendedSequences))
                delay(200)
            }
            send(V2.tagSequence())
            delay(200)
            val time = Triple(now.hour, now.minute, now.second).takeIf { info.extendedSequences }
            send(V2.runSequence(time, info.extendedSequences))
        }
    }

    override fun setTarget(halfDegrees: Int) {
        val clamped = halfDegrees.coerceIn(temperatureRange(_state.value.mode, isV2))
        if (isV2) v2Scene { V2.scene(it, temperature = clamped) } else send(V3.setTemperature(clamped))
    }

    override fun setFan(step: Int) {
        if (isV2) v2Scene { V2.scene(it, fanStep = step) } else send(V3.setFan(step))
    }

    override fun setRuntime(minutes: Int) {
        val clamped = minutes.coerceIn(1, _state.value.mode.maxMinutes)
        if (isV2) v2Scene { V2.scene(it, seconds = clamped * 60) } else send(V3.setRuntime(clamped))
    }

    /** The V2 sends temperature, fan and timer together, so it needs the latest status to repeat what isn't changing. */
    private fun v2Scene(build: (V2Status) -> ByteArray) {
        val status = v2Status
        if (status == null) {
            _state.update { it.copy(note = "Waiting for the BedJet's status. Try again in a moment.") }
            return
        }
        send(build(status))
    }

    private fun send(data: ByteArray) {
        if (_state.value.connection != Connection.READY) return
        enqueue(write(data))
    }

    // MARK: Receiving

    private fun receive(value: ByteArray) {
        if (isV2) {
            uartBuffer += value
            val (packets, rest) = V2.takePackets(uartBuffer)
            uartBuffer = rest
            for (packet in packets) {
                V2.reply(0x0B, packet)?.let { V2.parseInfo(it) }?.let { v2Info = it }
                V2.parseStatus(packet)?.let { status ->
                    v2Status = status
                    publish(status.mode, status.actual, status.target, status.fanStep, status.remainingSeconds, status.programStep)
                }
            }
        } else if (value.size >= 20 && value[1].toInt() == 0x56 && value[3].toInt() == 1) {
            // Same check as Codec.isStatusStart in the iOS app. Continuation chunks are ignored.
            V3.parseStatus(value)?.let { status ->
                publish(status.mode, status.actual, status.target, status.fanStep, status.remainingSeconds, status.programStep ?: 0)
            }
        }
    }

    private fun publish(mode: Mode, actual: Int, target: Int, fanStep: Int, remaining: Int, programStep: Int) {
        _state.update {
            it.copy(
                mode = mode,
                actualHalfDegrees = actual,
                targetHalfDegrees = target,
                fanStep = fanStep,
                remainingSeconds = remaining,
                programStep = programStep,
                note = null,
            )
        }
    }

    // MARK: GATT operation queue

    private fun write(data: ByteArray): (BluetoothGatt) -> Boolean = { gatt ->
        val characteristic = commandCharacteristic
        characteristic != null && writeCharacteristic(gatt, characteristic, data)
    }

    private fun enqueue(operation: (BluetoothGatt) -> Boolean) {
        operations.addLast(operation)
        pump()
    }

    /** Runs queued operations until one is in flight, then waits for its callback. */
    private fun pump() {
        val gatt = gatt ?: return
        while (!busy && operations.isNotEmpty()) {
            busy = operations.removeFirst()(gatt)
        }
    }

    private fun finishOperation() {
        busy = false
        pump()
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(characteristic)
        }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, data) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = data
            gatt.writeDescriptor(descriptor)
        }

    // MARK: Cleanup

    private fun cleanup() {
        gatt?.close()
        gatt = null
        commandCharacteristic = null
        notifyCharacteristic = null
        v2Status = null
        uartBuffer = ByteArray(0)
        v2Info = null
        scope.coroutineContext.cancelChildren()
        operations.clear()
        busy = false
    }

    private fun fail(message: String) {
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
        cleanup()
        _state.update { BedJetState(connection = Connection.FAILED, note = message) }
    }

    private companion object {
        fun uuid(short: String): UUID = UUID.fromString("0000$short-BED0-0080-AA55-4265644A6574")

        val V3_SERVICE: UUID = uuid("1000")
        val V3_STATUS: UUID = uuid("2000")
        val V3_COMMAND: UUID = uuid("2004")

        val UART_SERVICE: UUID = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")
        val UART_NOTIFY: UUID = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")
        val UART_WRITE: UUID = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3")

        val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
