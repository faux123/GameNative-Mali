package app.gamenative.utils

import app.gamenative.utils.CustomDriverSupport.DriverFamily
import app.gamenative.utils.CustomDriverSupport.GpuVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomDriverSupportTest {

    @Test
    fun libraryNameIdentifiesTheMesaDriver() {
        assertEquals(DriverFamily.PANVK, CustomDriverSupport.familyOfLibrary("libvulkan_panfrost.so"))
        assertEquals(DriverFamily.TURNIP, CustomDriverSupport.familyOfLibrary("libvulkan_freedreno.so"))
        assertEquals(DriverFamily.QUALCOMM, CustomDriverSupport.familyOfLibrary("vulkan.adreno.so"))
        assertEquals(DriverFamily.UNKNOWN, CustomDriverSupport.familyOfLibrary("libvulkan_custom.so"))
        assertEquals(DriverFamily.UNKNOWN, CustomDriverSupport.familyOfLibrary(null))
    }

    // Ids copied from the online driver catalog and the bundled version list on 2026-10-05.
    @Test
    fun catalogAndBundledNamesAreClassified() {
        val adrenoOnly = listOf(
            "turnip_v26.0.0_R8_b5", "Turnip_Gen8_V25", "turnip25.3.0_R3_Gmem",
            "qcom-849.0", "8eGen5-842.8", "8Elite-800.51", "8Elite_800.22", "Adreno_819", "v762", "v805",
        )
        adrenoOnly.forEach { name ->
            val family = CustomDriverSupport.familyOfLabel(name)
            assertTrue("$name -> $family", family == DriverFamily.TURNIP || family == DriverFamily.QUALCOMM)
        }
        assertEquals(DriverFamily.PANVK, CustomDriverSupport.familyOfLabel("PanVK-kbase-ace-b14"))
        assertEquals(DriverFamily.PANVK, CustomDriverSupport.familyOfLabel("EXP_panvk_g57_rg556_lab1"))
        assertEquals(DriverFamily.UNKNOWN, CustomDriverSupport.familyOfLabel("my-driver"))
        // A PanVK name wins over the Qualcomm version pattern, and a bare "8e" prefix is not a Qualcomm id.
        assertEquals(DriverFamily.PANVK, CustomDriverSupport.familyOfLabel("v240-panvk"))
        assertEquals(DriverFamily.UNKNOWN, CustomDriverSupport.familyOfLabel("8e1_mesa_test"))
    }

    @Test
    fun maliUsesPanvkAndUnknownButNeverAdrenoDrivers() {
        assertTrue(CustomDriverSupport.isUsable(GpuVendor.MALI, DriverFamily.PANVK))
        assertTrue(CustomDriverSupport.isUsable(GpuVendor.MALI, DriverFamily.UNKNOWN))
        assertFalse(CustomDriverSupport.isUsable(GpuVendor.MALI, DriverFamily.TURNIP))
        assertFalse(CustomDriverSupport.isUsable(GpuVendor.MALI, DriverFamily.QUALCOMM))
    }

    @Test
    fun adrenoKeepsItsDriversAndRejectsPanvk() {
        assertTrue(CustomDriverSupport.isUsable(GpuVendor.ADRENO, DriverFamily.TURNIP))
        assertTrue(CustomDriverSupport.isUsable(GpuVendor.ADRENO, DriverFamily.QUALCOMM))
        assertTrue(CustomDriverSupport.isUsable(GpuVendor.ADRENO, DriverFamily.UNKNOWN))
        assertFalse(CustomDriverSupport.isUsable(GpuVendor.ADRENO, DriverFamily.PANVK))
    }

    @Test
    fun otherGpusSeeEverything() {
        DriverFamily.values().forEach { assertTrue(CustomDriverSupport.isUsable(GpuVendor.OTHER, it)) }
    }

    @Test
    fun bundledListOnMaliKeepsSystemAndDropsAdrenoEntries() {
        val bundled = listOf("System", "v762", "v805", "Turnip_Gen8_V25", "turnip25.1.0", "turnip26.0.0_R8")
        assertEquals(listOf("System"), CustomDriverSupport.filterLabels(GpuVendor.MALI, bundled))
        assertEquals(bundled, CustomDriverSupport.filterLabels(GpuVendor.ADRENO, bundled))
        assertEquals(bundled, CustomDriverSupport.filterLabels(GpuVendor.OTHER, bundled))
    }

    // Labels from R.array.graphics_driver_entries (the glibc container driver list).
    @Test
    fun glibcDriverListOnMaliKeepsOnlyUniversalDrivers() {
        val entries = listOf(
            "Vortek (Universal)", "Turnip (Adreno)", "VirGL (Universal)", "Adreno (Adreno)", "SD 8 Elite (SD 8 Elite)",
        )
        assertEquals(
            listOf("Vortek (Universal)", "VirGL (Universal)"),
            CustomDriverSupport.filterLabels(GpuVendor.MALI, entries),
        )
        assertEquals(entries, CustomDriverSupport.filterLabels(GpuVendor.ADRENO, entries))
    }

    // "0" is the stored value of the "load through wrapper" switch when it is off.
    @Test
    fun directTurnipIcdIsNeverChosenOnMali() {
        assertTrue(CustomDriverSupport.usesDirectTurnipIcd(GpuVendor.ADRENO, "Turnip v26.2.0 R4", "0"))
        assertTrue(CustomDriverSupport.usesDirectTurnipIcd(GpuVendor.OTHER, "turnip25.1.0", "0"))
        assertFalse(CustomDriverSupport.usesDirectTurnipIcd(GpuVendor.ADRENO, "Turnip v26.2.0 R4", "1"))
        assertFalse(CustomDriverSupport.usesDirectTurnipIcd(GpuVendor.ADRENO, "System", "0"))
        assertFalse(CustomDriverSupport.usesDirectTurnipIcd(GpuVendor.ADRENO, null, "0"))
        assertFalse(CustomDriverSupport.usesDirectTurnipIcd(GpuVendor.MALI, "Turnip v26.2.0 R4", "0"))
    }

    @Test
    fun catalogOnMaliOffersOnlyPanvkEntries() {
        fun entry(id: String) = ManifestEntry(id = id, name = "$id.zip", url = "https://example.invalid/$id.zip")
        val catalog = listOf(entry("turnip_v26.0.0_R8"), entry("qcom-849.0"), entry("mystery-1.0"), entry("panvk-26.3"))

        assertEquals(listOf("panvk-26.3"), CustomDriverSupport.filterCatalog(GpuVendor.MALI, catalog).map { it.id })
        assertEquals(
            listOf("turnip_v26.0.0_R8", "qcom-849.0", "mystery-1.0"),
            CustomDriverSupport.filterCatalog(GpuVendor.ADRENO, catalog).map { it.id },
        )
        assertEquals(catalog, CustomDriverSupport.filterCatalog(GpuVendor.OTHER, catalog))
    }
}
