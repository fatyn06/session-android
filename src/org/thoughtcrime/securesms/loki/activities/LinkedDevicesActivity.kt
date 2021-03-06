package org.thoughtcrime.securesms.loki.activities

import android.os.Bundle
import androidx.loader.app.LoaderManager
import androidx.loader.content.Loader
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_linked_devices.*
import network.loki.messenger.R
import nl.komponents.kovenant.ui.failUi
import nl.komponents.kovenant.ui.successUi
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil
import org.thoughtcrime.securesms.crypto.storage.TextSecureSessionStore
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.devicelist.Device
import org.thoughtcrime.securesms.logging.Log
import org.thoughtcrime.securesms.loki.dialogs.*
import org.thoughtcrime.securesms.loki.protocol.shelved.SyncMessagesProtocol
import org.thoughtcrime.securesms.loki.utilities.recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.push.SignalServiceAddress
import org.whispersystems.signalservice.loki.api.fileserver.FileServerAPI
import java.util.*
import kotlin.concurrent.schedule

class LinkedDevicesActivity : PassphraseRequiredActionBarActivity, LoaderManager.LoaderCallbacks<List<Device>>, DeviceClickListener, EditDeviceNameDialogDelegate, LinkDeviceMasterModeDialogDelegate {
    private var devices = listOf<Device>()
        set(value) { field = value; linkedDevicesAdapter.devices = value }

    private val linkedDevicesAdapter by lazy {
        val result = LinkedDevicesAdapter(this)
        result.deviceClickListener = this
        result
    }

    // region Lifecycle
    constructor() : super()

    override fun onCreate(savedInstanceState: Bundle?, isReady: Boolean) {
        super.onCreate(savedInstanceState, isReady)
        setContentView(R.layout.activity_linked_devices)
        supportActionBar!!.title = resources.getString(R.string.activity_linked_devices_title)
        recyclerView.adapter = linkedDevicesAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        linkDeviceButton.setOnClickListener { linkDevice() }
        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_linked_devices, menu)
        return true
    }
    // endregion

    // region Updating
    override fun onCreateLoader(id: Int, bundle: Bundle?): Loader<List<Device>> {
        return LinkedDevicesLoader(this)
    }

    override fun onLoadFinished(loader: Loader<List<Device>>, devices: List<Device>?) {
        update(devices ?: listOf())
    }

    override fun onLoaderReset(loader: Loader<List<Device>>) {
        update(listOf())
    }

    private fun update(devices: List<Device>) {
        this.devices = devices
        emptyStateContainer.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun handleDeviceNameChanged(device: Device) {
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }
    // endregion

    // region Interaction
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        when(id) {
            R.id.linkDeviceButton -> linkDevice()
            else -> { /* Do nothing */ }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun linkDevice() {
        if (devices.isEmpty()) {
            val linkDeviceDialog = LinkDeviceMasterModeDialog()
            linkDeviceDialog.delegate = this
            linkDeviceDialog.show(supportFragmentManager, "Link Device Dialog")
        } else {
            val builder = AlertDialog.Builder(this)
            builder.setTitle(resources.getString(R.string.activity_linked_devices_multi_device_limit_reached_dialog_title))
            builder.setMessage(resources.getString(R.string.activity_linked_devices_multi_device_limit_reached_dialog_explanation))
            builder.setPositiveButton(resources.getString(R.string.ok), { dialog, _ -> dialog.dismiss() })
            builder.create().show()
        }
    }

    override fun onDeviceClick(device: Device) {
        val bottomSheet = DeviceEditingOptionsBottomSheet()
        bottomSheet.onEditTapped = {
            bottomSheet.dismiss()
            val editDeviceNameDialog = EditDeviceNameDialog()
            editDeviceNameDialog.device = device
            editDeviceNameDialog.delegate = this
            editDeviceNameDialog.show(supportFragmentManager, "Edit Device Name Dialog")
        }
        bottomSheet.onUnlinkTapped = {
            bottomSheet.dismiss()
            unlinkDevice(device.id)
        }
        bottomSheet.show(supportFragmentManager, bottomSheet.tag)
    }

    private fun unlinkDevice(slaveDevicePublicKey: String) {
        val userPublicKey = TextSecurePreferences.getLocalNumber(this)
        val apiDB = DatabaseFactory.getLokiAPIDatabase(this)
        val deviceLinks = apiDB.getDeviceLinks(userPublicKey)
        val deviceLink = deviceLinks.find { it.masterPublicKey == userPublicKey && it.slavePublicKey == slaveDevicePublicKey }
        if (deviceLink == null) {
            return Toast.makeText(this, R.string.activity_linked_devices_unlinking_failed_message, Toast.LENGTH_LONG).show()
        }
        FileServerAPI.shared.setDeviceLinks(setOf()).successUi {
            DatabaseFactory.getLokiAPIDatabase(this).clearDeviceLinks(userPublicKey)
            deviceLinks.forEach { deviceLink ->
                // We don't use PushEphemeralMessageJob because want these messages to send before the pre key and
                // session associated with the slave device have been deleted
                val unlinkingRequest = SignalServiceDataMessage.newBuilder()
                    .withTimestamp(System.currentTimeMillis())
                    .asDeviceUnlinkingRequest(true)
                val messageSender = ApplicationContext.getInstance(this@LinkedDevicesActivity).communicationModule.provideSignalMessageSender()
                val address = SignalServiceAddress(deviceLink.slavePublicKey)
                try {
                    val udAccess = UnidentifiedAccessUtil.getAccessFor(this@LinkedDevicesActivity, recipient(this@LinkedDevicesActivity, deviceLink.slavePublicKey))
                    messageSender.sendMessage(0, address, udAccess, unlinkingRequest.build()) // The message ID doesn't matter
                } catch (e: Exception) {
                    Log.d("Loki", "Failed to send unlinking request due to error: $e.")
                    throw e
                }
                DatabaseFactory.getLokiPreKeyBundleDatabase(this).removePreKeyBundle(deviceLink.slavePublicKey)
                val sessionStore = TextSecureSessionStore(this@LinkedDevicesActivity)
                sessionStore.deleteAllSessions(deviceLink.slavePublicKey)
            }
            LoaderManager.getInstance(this).restartLoader(0, null, this)
            Toast.makeText(this, R.string.activity_linked_devices_unlinking_successful_message, Toast.LENGTH_LONG).show()
        }.failUi {
            Toast.makeText(this, R.string.activity_linked_devices_unlinking_failed_message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDeviceLinkRequestAuthorized() {
        SyncMessagesProtocol.syncAllClosedGroups(this)
        SyncMessagesProtocol.syncAllOpenGroups(this)
        Timer().schedule(4000) { // Not the best way to do this but the idea is to wait for the closed groups sync to go through first
            SyncMessagesProtocol.syncAllContacts(this@LinkedDevicesActivity)
        }
        LoaderManager.getInstance(this).restartLoader(0, null, this)
    }

    override fun onDeviceLinkAuthorizationFailed() {
        Toast.makeText(this, R.string.activity_linked_devices_linking_failed_message, Toast.LENGTH_LONG).show()
    }

    override fun onDeviceLinkCanceled() {
        // Do nothing
    }
    // endregion
}