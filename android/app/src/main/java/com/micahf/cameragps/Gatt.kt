package com.micahf.cameragps

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class GattException(message: String) : Exception(message)

/** [withTimeout], but running out of time is a failed operation rather than a cancellation. */
private suspend fun <T> within(ms: Long, what: String, block: suspend CoroutineScope.() -> T): T = try {
    withTimeout(ms, block)
} catch (e: TimeoutCancellationException) {
    throw GattException("$what: timed out")
}

/** The callback that completes a GATT operation. */
internal enum class OpKind { WRITE, READ, MTU, DESCRIPTOR_WRITE }

/**
 * The one operation in flight. Only a callback of the same kind, for the same
 * characteristic, completes it, so a late callback from an operation that
 * timed out can't complete the next one.
 */
internal class InFlight(private val kind: OpKind, private val uuid: UUID?) {
    val result = CompletableDeferred<Pair<Int, ByteArray>>()

    /** Completes with a callback's status and value if the callback belongs to this operation. */
    fun offer(kind: OpKind, uuid: UUID?, status: Int, value: ByteArray = ByteArray(0)): Boolean =
        kind == this.kind && uuid == this.uuid && result.complete(status to value)
}

/**
 * Coroutine wrapper around [BluetoothGatt]. Android allows one GATT operation
 * in flight at a time, so operations are serialised with a mutex.
 *
 * Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class Gatt private constructor(private val device: BluetoothDevice) {
    private lateinit var gatt: BluetoothGatt
    private val opLock = Mutex()
    @Volatile private var pending: InFlight? = null
    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Int>()
    private val listeners = ConcurrentHashMap<UUID, (ByteArray) -> Unit>()

    /** Completes when the link drops, with the HCI reason. */
    val disconnected = CompletableDeferred<Int>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val reason = GattException("disconnected (status $status)")
                    connected.completeExceptionally(reason)
                    servicesDiscovered.completeExceptionally(reason)
                    pending?.result?.completeExceptionally(reason)
                    disconnected.complete(status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDiscovered.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (pending?.offer(OpKind.WRITE, c.uuid, status) == false) Log.w(TAG, "stray write callback")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (pending?.offer(OpKind.READ, c.uuid, status, value) == false) Log.w(TAG, "stray read callback")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (pending?.offer(OpKind.MTU, null, status) == false) Log.w(TAG, "stray MTU callback")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (pending?.offer(OpKind.DESCRIPTOR_WRITE, d.characteristic.uuid, status) == false) Log.w(TAG, "stray descriptor callback")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listeners[c.uuid]?.invoke(value)
        }
    }

    val isConnected: Boolean get() = !disconnected.isCompleted

    /** Set the link's connection interval (see [BluetoothGatt.requestConnectionPriority]). */
    fun requestPriority(priority: Int) {
        gatt.requestConnectionPriority(priority)
    }

    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val c = characteristic(service, characteristic)
        op("write $characteristic", OpKind.WRITE, characteristic) {
            gatt.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        val c = characteristic(service, characteristic)
        return op("read $characteristic", OpKind.READ, characteristic) {
            if (gatt.readCharacteristic(c)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
        }
    }

    /** Ask for a larger ATT MTU, so long values fit one read. */
    suspend fun requestMtu(mtu: Int) {
        op("request MTU", OpKind.MTU, null) {
            if (gatt.requestMtu(mtu)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
        }
    }

    /** Subscribe to indications, delivering each value to [onValue] on a binder thread. */
    suspend fun indicate(service: UUID, characteristic: UUID, onValue: (ByteArray) -> Unit) {
        val c = characteristic(service, characteristic)
        listeners[characteristic] = onValue
        if (!gatt.setCharacteristicNotification(c, true)) {
            throw GattException("enable indications on $characteristic")
        }
        val cccd = c.getDescriptor(CCCD) ?: throw GattException("no CCCD on $characteristic")
        op("subscribe $characteristic", OpKind.DESCRIPTOR_WRITE, characteristic) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        }
    }

    fun close() {
        gatt.disconnect()
        gatt.close()
        // close() suppresses the disconnect callback.
        disconnected.complete(-1)
    }

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt.getService(service)?.getCharacteristic(characteristic)
            ?: throw GattException("device has no $characteristic")

    private suspend fun op(what: String, kind: OpKind, uuid: UUID?, start: () -> Int): ByteArray = opLock.withLock {
        val op = InFlight(kind, uuid)
        pending = op
        try {
            val started = start()
            if (started != BluetoothStatusCodes.SUCCESS) throw GattException("$what: not started ($started)")
            val (status, value) = within(OP_TIMEOUT_MS, what) { op.result.await() }
            if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("$what: status $status")
            value
        } finally {
            pending = null
        }
    }

    companion object {
        private const val TAG = "Gatt"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OP_TIMEOUT_MS = 5_000L

        /**
         * Connect, bond if needed (and [bond] is set), and discover services.
         * Closes the connection if any step fails or [timeoutMs] passes before
         * the link is up.
         */
        suspend fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long, bond: Boolean = true): Gatt {
            val g = Gatt(device)
            g.gatt = device.connectGatt(context, false, g.callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw GattException("connectGatt failed")
            try {
                within(timeoutMs, "connect") { g.connected.await() }
                if (bond && device.bondState != BluetoothDevice.BOND_BONDED) {
                    bond(context, device)
                }
                val status = within(OP_TIMEOUT_MS * 2, "service discovery") {
                    if (!g.gatt.discoverServices()) throw GattException("discoverServices failed")
                    g.servicesDiscovered.await()
                }
                if (status != BluetoothGatt.GATT_SUCCESS) throw GattException("service discovery: status $status")
                return g
            } catch (e: Throwable) {
                g.close()
                throw e
            }
        }

        /** Bond, which shows the system pairing prompt. */
        private suspend fun bond(context: Context, device: BluetoothDevice) {
            val bonded = suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (d?.address != device.address) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDED -> finish(true)
                            BluetoothDevice.BOND_NONE -> finish(false)
                        }
                    }

                    fun finish(ok: Boolean) {
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(ok)
                    }
                }
                context.registerReceiver(
                    receiver,
                    IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                if (!device.createBond()) receiver.finish(false)
            }
            if (!bonded) throw GattException("bonding failed")
        }
    }
}
