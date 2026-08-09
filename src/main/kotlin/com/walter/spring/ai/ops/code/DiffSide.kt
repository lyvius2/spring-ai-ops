package com.walter.spring.ai.ops.code

import com.fasterxml.jackson.annotation.JsonCreator

enum class DiffSide {
    LEFT,
    RIGHT;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromRaw(raw: String): DiffSide = when (raw.uppercase()) {
            "LEFT" -> LEFT
            else -> RIGHT
        }
    }
}