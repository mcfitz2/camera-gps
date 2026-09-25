package com.micahf.cameragps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * [label] is for the ongoing notification; [title] and [detail] are the
 * friendlier wording on the Camera tab.
 */
enum class Link(val label: String, val title: String, val detail: String) {
    Idle("waiting for camera", "Waiting for camera", "Photos are tagged automatically whenever the camera is on."),
    Connecting("connecting", "Connecting…", "Found the camera."),
    AwaitingApproval("confirm pairing on camera", "Confirm on camera", "Accept the pairing request on the camera's screen."),
    Connected("connected", "Connected", "Getting a location fix."),
    Sending("sending location", "Tagging photos", "Your location is sent to the camera as you move."),
}

data class Status(
    val link: Link = Link.Idle,
    val lastSent: Sent? = null,
    val sendsThisSession: Int = 0,
    val message: String? = null,
)

data class Sent(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val atMillis: Long,
)

/** Process-wide status, written by [CameraService] and shown by [MainActivity]. */
object StatusStore {
    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state

    fun update(f: (Status) -> Status) = state.update(f)
}
