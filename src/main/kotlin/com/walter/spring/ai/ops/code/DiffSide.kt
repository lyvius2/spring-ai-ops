package com.walter.spring.ai.ops.code

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class DiffSide {
    LEFT,
    RIGHT;

    @JsonValue
    fun toJson(): String = name

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromRaw(raw: String): DiffSide = when (raw.uppercase()) {
            "LEFT" -> LEFT
            else -> RIGHT
        }
    }
}