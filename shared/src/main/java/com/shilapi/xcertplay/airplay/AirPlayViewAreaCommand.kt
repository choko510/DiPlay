package com.shilapi.xcertplay.airplay

data class AirPlayViewAreaRequest(
    val uuid: String?,
    val viewAreaIndex: Int?,
    val invalidIndex: Boolean,
) {
    fun validationError(declaredAreaCount: Int): String? = when {
        uuid == null -> "missing_display_uuid"
        !uuid.equals(AirPlayInfoPlist.MAIN_UUID, ignoreCase = true) -> "unknown_display_uuid"
        declaredAreaCount < 2 -> "dynamic_view_areas_not_declared"
        invalidIndex || viewAreaIndex == null -> "missing_or_invalid_index"
        viewAreaIndex !in 0 until declaredAreaCount -> "view_area_index_out_of_range"
        else -> null
    }
}

object AirPlayViewAreaCommandParser {
    fun parseIncoming(type: String, params: Map<String, Any?>): AirPlayViewAreaRequest? {
        if (type != "requestViewArea") return null
        val rawIndex = params["viewAreaIndex"]
        val index = (rawIndex as? Number)?.let(::toIntExact)
        return AirPlayViewAreaRequest(
            uuid = params["uuid"] as? String,
            viewAreaIndex = index,
            invalidIndex = rawIndex != null && index == null,
        )
    }

    private fun toIntExact(value: Number): Int? {
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
            return null
        }
        return number.toInt()
    }
}

internal object AirPlayViewAreaCommand {
    fun update(index: Int, declaredAreaCount: Int, animationDurationMillis: Int = 0): Map<String, Any?>? {
        if (declaredAreaCount != 2 || index !in 0 until declaredAreaCount || animationDurationMillis < 0) return null
        val adjacentAreas = (0 until declaredAreaCount).filter { it != index }
        return linkedMapOf(
            "type" to "updateViewArea",
            "params" to linkedMapOf(
                "uuid" to AirPlayInfoPlist.MAIN_UUID,
                "viewAreaIndex" to index,
                "animationDurationMillis" to animationDurationMillis,
                "adjacentViewAreas" to adjacentAreas,
            ),
        )
    }
}

enum class ViewAreaCommandWriteResult {
    WRITTEN,
    SESSION_CLOSED,
    DYNAMIC_AREAS_NOT_DECLARED,
    INVALID_INDEX,
    EVENT_CHANNEL_NOT_READY,
    WRITE_FAILED,
}
