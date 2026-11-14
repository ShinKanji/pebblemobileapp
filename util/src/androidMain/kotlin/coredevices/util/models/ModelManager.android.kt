package coredevices.util.models

import android.os.Build
import coredevices.util.Platform

/** True on Qualcomm SoCs, whose Hexagon NPU the Cactus engine can target via NPU-compiled model bundles. */
actual fun Platform.supportsNPU(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return false
    }
    return Build.SOC_MANUFACTURER.contains("qualcomm", ignoreCase = true) ||
        Build.SOC_MODEL.startsWith("SM", ignoreCase = true)
}

private val HEAVY_MODELS = listOf(
    "Tensor G3",
    "Tensor G4",
    "Tensor G5",
    "Tensor G6",
    "SM8650", // Snapdragon 8 Gen 3
    "SM8750", // Snapdragon 8 Elite
    "SM8850", // Snapdragon 8 Elite Gen 5
)
actual fun Platform.supportsHeavyCPU(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return false
    }
    val soc = Build.SOC_MODEL
    return HEAVY_MODELS.any { soc.contains(it, ignoreCase = true) }
}