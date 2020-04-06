package com.geeksville.mesh.ui

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import androidx.compose.Composable
import androidx.compose.Model
import androidx.compose.frames.modelMapOf
import androidx.compose.onCommit
import androidx.ui.core.ContextAmbient
import androidx.ui.core.Text
import androidx.ui.layout.Column
import androidx.ui.layout.LayoutGravity
import androidx.ui.material.CircularProgressIndicator
import androidx.ui.material.MaterialTheme
import androidx.ui.material.ProvideEmphasis
import androidx.ui.material.RadioGroup
import androidx.ui.tooling.preview.Preview
import com.geeksville.android.Logging
import com.geeksville.mesh.service.RadioInterfaceService
import com.geeksville.util.exceptionReporter


@Model
object ScanUIState {
    var selectedMacAddr: String? = null
    var errorText: String? = null

    val devices = modelMapOf<String, BTScanEntry>()

    /// Change to a new macaddr selection, updating GUI and radio
    fun changeSelection(context: Context, newAddr: String) {
        ScanState.info("Changing BT device to $newAddr")
        selectedMacAddr = newAddr
        RadioInterfaceService.setBondedDeviceAddress(context, newAddr)
    }
}

/// FIXME, remove once compose has better lifecycle management
object ScanState : Logging {
    var scanner: BluetoothLeScanner? = null
    var callback: ScanCallback? = null // SUPER NASTY FIXME

    fun stopScan() {
        if (callback != null) {
            debug("stopping scan")
            try {
                scanner!!.stopScan(callback)
            } catch (ex: Throwable) {
                warn("Ignoring error stopping scan, probably BT adapter was disabled suddenly: ${ex.message}")
            }
            callback = null
        }
    }
}

@Model
data class BTScanEntry(val name: String, val macAddress: String, val bonded: Boolean) {
    val isSelected get() = macAddress == ScanUIState.selectedMacAddr
}


@Composable
fun BTScanScreen() {
    val context = ContextAmbient.current

    /// Note: may be null on platforms without a bluetooth driver (ie. the emulator)
    val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    analyticsScreen(name = "settings")
    onCommit(AppStatus.currentScreen) {
        ScanState.debug("BTScan component active")
        ScanUIState.selectedMacAddr = RadioInterfaceService.getBondedDeviceAddress(context)

        val scanCallback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                val msg = "Unexpected bluetooth scan failure: $errorCode"
                // error code2 seeems to be indicate hung bluetooth stack
                ScanUIState.errorText = msg
                ScanState.reportError(msg)
            }

            // For each device that appears in our scan, ask for its GATT, when the gatt arrives,
            // check if it is an eligable device and store it in our list of candidates
            // if that device later disconnects remove it as a candidate
            override fun onScanResult(callbackType: Int, result: ScanResult) {

                val addr = result.device.address
                // prevent logspam because weill get get lots of redundant scan results
                val isBonded = result.device.bondState == BluetoothDevice.BOND_BONDED
                val oldEntry = ScanUIState.devices[addr]
                if (oldEntry == null || oldEntry.bonded != isBonded) {
                    val entry = BTScanEntry(
                        result.device.name,
                        addr,
                        isBonded
                    )
                    ScanState.debug("onScanResult ${entry}")
                    ScanUIState.devices[addr] = entry

                    // If nothing was selected, by default select the first thing we see
                    if (ScanUIState.selectedMacAddr == null && entry.bonded)
                        ScanUIState.changeSelection(context, addr)
                }
            }
        }
        if (bluetoothAdapter == null) {
            ScanState.warn("No bluetooth adapter.  Running under emulation?")

            val testnodes = listOf(
                BTScanEntry("Meshtastic_ab12", "xx", false),
                BTScanEntry("Meshtastic_32ac", "xb", true)
            )

            ScanUIState.devices.putAll(testnodes.map { it.macAddress to it })

            // If nothing was selected, by default select the first thing we see
            if (ScanUIState.selectedMacAddr == null)
                ScanUIState.changeSelection(context, testnodes.first().macAddress)
        } else {
            /// The following call might return null if the user doesn't have bluetooth access permissions
            val s: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

            if (s == null) {
                ScanUIState.errorText =
                    "This application requires bluetooth access. Please grant access in android settings."
            } else {
                ScanState.debug("starting scan")

                // filter and only accept devices that have a sw update service
                val filter =
                    ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid(RadioInterfaceService.BTM_SERVICE_UUID))
                        .build()

                val settings =
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                s.startScan(listOf(filter), settings, scanCallback)
                ScanState.scanner = s
                ScanState.callback = scanCallback
            }
        }

        onDispose {
            ScanState.debug("BTScan component deactivated")
            ScanState.stopScan()
        }
    }

    Column {
        if (ScanUIState.errorText != null) {
            Text(text = ScanUIState.errorText!!)
        } else {
            if (ScanUIState.devices.isEmpty()) {
                Text(
                    text = "Looking for Meshtastic devices... (zero found)",
                    modifier = LayoutGravity.Center
                )

                CircularProgressIndicator() // Show that we are searching still
            } else {
                // val allPaired = bluetoothAdapter?.bondedDevices.orEmpty().map { it.address }.toSet()

                /* Only let user select paired devices
                val paired = devices.values.filter { allPaired.contains(it.macAddress) }
                if (paired.size < devices.size) {
                    Text(
                        "Warning: there are nearby Meshtastic devices that are not paired with this phone.  Before you can select a device, you will need to pair it in Bluetooth Settings."
                    )
                } */

                RadioGroup {
                    Column {
                        ScanUIState.devices.values.forEach {
                            // disabled pending https://issuetracker.google.com/issues/149528535
                            ProvideEmphasis(emphasis = if (it.bonded) MaterialTheme.emphasisLevels().high else MaterialTheme.emphasisLevels().disabled) {
                                RadioGroupTextItem(
                                    selected = (it.isSelected),
                                    onSelect = {
                                        // If the device is paired, let user select it, otherwise start the pairing flow
                                        if (it.bonded) {
                                            ScanUIState.changeSelection(context, it.macAddress)
                                        } else {
                                            ScanState.info("Starting bonding for $it")

                                            // We need this receiver to get informed when the bond attempt finished
                                            val bondChangedReceiver = object : BroadcastReceiver() {

                                                override fun onReceive(
                                                    context: Context,
                                                    intent: Intent
                                                ) = exceptionReporter {
                                                    val state =
                                                        intent.getIntExtra(
                                                            BluetoothDevice.EXTRA_BOND_STATE,
                                                            -1
                                                        )
                                                    ScanState.debug("Received bond state changed $state")
                                                    context.unregisterReceiver(this)
                                                    if (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_BONDING) {
                                                        ScanState.debug("Bonding completed, connecting service")
                                                        ScanUIState.changeSelection(
                                                            context,
                                                            it.macAddress
                                                        )
                                                    }
                                                }
                                            }

                                            val filter = IntentFilter()
                                            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                                            context.registerReceiver(bondChangedReceiver, filter)

                                            // We ignore missing BT adapters, because it lets us run on the emulator
                                            bluetoothAdapter
                                                ?.getRemoteDevice(it.macAddress)
                                                ?.createBond()
                                        }
                                    },
                                    text = it.name
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Preview
@Composable
fun btScanScreenPreview() {
    BTScanScreen()
}