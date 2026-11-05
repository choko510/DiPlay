package com.shilapi.xcertplay.airplay

internal class AirPlayTouchSlots(private val slotCount: Int = 2) {
    internal class Assignment internal constructor(
        val contacts: List<AirPlayContact>,
        internal val nextPointerIds: Array<Int?>,
        internal val nextHeld: Array<AirPlayContact?>,
    )

    private val pointerIds = arrayOfNulls<Int>(slotCount)
    private val held = arrayOfNulls<AirPlayContact>(slotCount)
    private val releasePending = BooleanArray(slotCount)

    init {
        require(slotCount > 0)
    }

    fun prepare(contacts: List<AirPlayContact>): Assignment {
        val nextPointerIds = pointerIds.copyOf()
        val nextHeld = held.copyOf()
        val blocked = releasePending.copyOf()
        val report = arrayOfNulls<AirPlayContact>(slotCount)

        for (slot in 0 until slotCount) {
            if (!blocked[slot]) continue
            report[slot] = held[slot]?.copy(id = slot, down = false) ?: emptyContact(slot)
            nextPointerIds[slot] = null
            nextHeld[slot] = null
        }

        for (contact in contacts) {
            var slot = nextPointerIds.indexOf(contact.id)
            if (slot < 0 && contact.down) {
                slot = nextPointerIds.indices.firstOrNull {
                    !blocked[it] && nextPointerIds[it] == null && report[it] == null
                } ?: -1
            }
            if (slot < 0 || report[slot] != null) continue

            report[slot] = contact.copy(id = slot)
            if (contact.down) {
                nextPointerIds[slot] = contact.id
                nextHeld[slot] = contact.copy(id = slot)
            } else {
                nextPointerIds[slot] = null
                nextHeld[slot] = null
            }
        }

        for (slot in 0 until slotCount) {
            if (report[slot] != null) continue
            report[slot] = held[slot]?.copy(id = slot, down = false) ?: emptyContact(slot)
            nextPointerIds[slot] = null
            nextHeld[slot] = null
        }

        return Assignment(report.map { requireNotNull(it) }, nextPointerIds, nextHeld)
    }

    fun complete(assignment: Assignment, delivered: Boolean) {
        if (delivered) {
            for (slot in 0 until slotCount) {
                pointerIds[slot] = assignment.nextPointerIds[slot]
                held[slot] = assignment.nextHeld[slot]
                releasePending[slot] = false
            }
            return
        }

        for (slot in 0 until slotCount) {
            if (!assignment.contacts[slot].down && held[slot] != null) {
                releasePending[slot] = true
            }
        }
    }

    private fun emptyContact(slot: Int) = AirPlayContact(slot, 0.0, 0.0, down = false)
}
