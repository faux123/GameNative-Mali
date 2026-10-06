package app.gamenative.utils

import android.content.Context
import com.winlator.contents.AdrenotoolsManager
import com.winlator.core.GPUInformation
import java.util.Locale

/**
 * Decides which custom Vulkan driver packages apply to the GPU in this device.
 *
 * A custom driver package is a zip with a `meta.json` and one Vulkan library. The loader does
 * not care which GPU the library was built for, so a Turnip package selected on a Mali device
 * (or a PanVK package on an Adreno device) only fails later, as a black screen. Everything that
 * lists or loads custom drivers asks this object first.
 */
object CustomDriverSupport {
    const val SYSTEM_DRIVER = "System"

    // Qualcomm driver packages named by version alone: v762, v805.
    private val QUALCOMM_VERSION_NAME = Regex("^v\\d{3}\\b.*")

    enum class GpuVendor { ADRENO, MALI, OTHER }

    enum class DriverFamily {
        /** Mesa Turnip, for Adreno. */
        TURNIP,

        /** Qualcomm's proprietary Adreno driver. */
        QUALCOMM,

        /** Mesa PanVK, for Mali. */
        PANVK,
        UNKNOWN,
    }

    /** The library name in `meta.json` is the ground truth: it names the Mesa driver built into the package. */
    fun familyOfLibrary(libraryName: String?): DriverFamily {
        val lib = libraryName.orEmpty().lowercase(Locale.ENGLISH)
        return when {
            lib.contains("panfrost") || lib.contains("panvk") -> DriverFamily.PANVK
            lib.contains("freedreno") || lib.contains("turnip") -> DriverFamily.TURNIP
            lib.contains("adreno") -> DriverFamily.QUALCOMM
            else -> DriverFamily.UNKNOWN
        }
    }

    /**
     * For entries that have no `meta.json` to read: bundled version names and the ids in the
     * online driver catalog (`turnip_v26.0.0_R8`, `qcom-849.0`, `8Elite-800.51`, `Adreno_819`, `v805`).
     */
    fun familyOfLabel(label: String): DriverFamily {
        val l = label.lowercase(Locale.ENGLISH)
        val isQualcomm = l.contains("adreno") || l.contains("qcom") || l.contains("elite") ||
            l.startsWith("8egen") || QUALCOMM_VERSION_NAME.matches(l)
        return when {
            l.contains("panvk") || l.contains("panfrost") -> DriverFamily.PANVK
            l.contains("turnip") || l.contains("freedreno") -> DriverFamily.TURNIP
            isQualcomm -> DriverFamily.QUALCOMM
            else -> DriverFamily.UNKNOWN
        }
    }

    /**
     * An UNKNOWN package is one the user imported on purpose, so it stays usable. Devices that
     * are neither Adreno nor Mali keep the upstream behavior and see everything.
     */
    fun isUsable(vendor: GpuVendor, family: DriverFamily): Boolean = when (vendor) {
        GpuVendor.MALI -> family == DriverFamily.PANVK || family == DriverFamily.UNKNOWN
        GpuVendor.ADRENO -> family != DriverFamily.PANVK
        GpuVendor.OTHER -> true
    }

    /** Bundled version names. "System" always stays, because it is the fallback. */
    fun filterLabels(vendor: GpuVendor, labels: List<String>): List<String> =
        labels.filter { it.equals(SYSTEM_DRIVER, ignoreCase = true) || isUsable(vendor, familyOfLabel(it)) }

    /**
     * Online catalog entries. The catalog is an Adreno catalog today, so on Mali an entry must
     * positively identify as PanVK to be offered; an unrecognized name is not worth a download.
     */
    fun filterCatalog(vendor: GpuVendor, entries: List<ManifestEntry>): List<ManifestEntry> =
        entries.filter { isCatalogIdOffered(vendor, it.id) }

    fun isCatalogIdOffered(vendor: GpuVendor, id: String): Boolean {
        val family = familyOfLabel(id)
        return if (vendor == GpuVendor.MALI) family == DriverFamily.PANVK else isUsable(vendor, family)
    }

    // GPUInformation loads a native library in its static initializer. On the JVM (unit tests,
    // Compose previews) that throws an Error, so the lookup degrades to OTHER, which is the
    // unfiltered upstream behavior.
    fun gpuVendor(context: Context): GpuVendor = runCatching {
        when {
            GPUInformation.isMaliGPU(context) -> GpuVendor.MALI
            GPUInformation.isAdrenoGPU(context) -> GpuVendor.ADRENO
            else -> GpuVendor.OTHER
        }
    }.getOrDefault(GpuVendor.OTHER)

    /** Family of an installed package, read from its `meta.json`, with the package name as fallback. */
    fun installedFamily(context: Context, driverId: String): DriverFamily {
        val fromLibrary = familyOfLibrary(AdrenotoolsManager(context).getLibraryName(driverId))
        return if (fromLibrary != DriverFamily.UNKNOWN) fromLibrary else familyOfLabel(driverId)
    }

    fun filterInstalled(context: Context, driverIds: List<String>): List<String> {
        val vendor = gpuVendor(context)
        return driverIds.filter { isUsable(vendor, installedFamily(context, it)) }
    }

    /**
     * True when the launch should use the Mesa Turnip ICD inside the image instead of the wrapper.
     * That ICD only drives Adreno. A container saved on an Adreno device can carry a Turnip
     * version with the wrapper switch off, and on Mali it must still get the wrapper ICD.
     */
    fun usesDirectTurnipIcd(vendor: GpuVendor, driverVersion: String?, loadThroughWrapper: String?): Boolean =
        vendor != GpuVendor.MALI &&
            driverVersion.orEmpty().lowercase(Locale.ENGLISH).contains("turnip") && loadThroughWrapper == "0"

    /**
     * The driver id to load at launch. A container can still name a driver for another GPU
     * (a config shared from an Adreno device, or one saved before this check existed), so this
     * falls back to the system driver instead of handing the loader a library that cannot work.
     */
    fun resolveForLaunch(context: Context, driverId: String?): String {
        if (driverId.isNullOrEmpty() || driverId.equals(SYSTEM_DRIVER, ignoreCase = true)) return SYSTEM_DRIVER
        return if (isUsable(gpuVendor(context), installedFamily(context, driverId))) driverId else SYSTEM_DRIVER
    }
}
