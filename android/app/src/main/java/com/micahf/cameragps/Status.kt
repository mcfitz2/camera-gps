package com.micahf.cameragps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Link(val label: String) {
    Idle("waiting for camera"),
    Connecting("connecting"),
    AwaitingApproval("confirm pairing on camera"),
    Connected("connected"),
    Sending("sending location"),
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
