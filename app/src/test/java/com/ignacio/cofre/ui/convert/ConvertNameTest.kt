package com.ignacio.cofre.ui.convert

import com.ignacio.cofre.kit.common.Loader
import com.ignacio.cofre.kit.modfolders.ConvertTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertNameTest {
    private val neoForge = ConvertTarget("26.2", Loader.NeoForge)

    @Test fun addsTheTarget() = assertEquals("Mods test mods · NeoForge 26.2", convertedName("Mods test mods", neoForge))

    @Test fun replacesAnEarlierConvertsSuffix() =
        assertEquals("Mods test mods · NeoForge 26.2", convertedName("Mods test mods · Fabric 26.2", neoForge))

    @Test fun replacesASuffixWithItsDedupNumber() =
        assertEquals("Survival · Fabric 26.3", convertedName("Survival · NeoForge 26.2.1 2", ConvertTarget("26.3", Loader.Fabric)))

    @Test fun keepsAMiddleDotThatIsNotALoader() =
        assertEquals("A · B · NeoForge 26.2", convertedName("A · B", neoForge))
}
