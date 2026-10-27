package dev.phoenix.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSkinsTest {
    @Test
    fun uuidPicksWhereTheSkinComesFrom() {
        // Floodgate: UUID(0, xuid).
        assertEquals(PlayerSkins.Source.Bedrock(0x000901f2a3b4c5d6), PlayerSkins.sourceOf("00000000-0000-0000-0009-01f2a3b4c5d6"))
        assertEquals(PlayerSkins.Source.Java("069a79f444e94726a5befca90e38aaf5"), PlayerSkins.sourceOf("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
        // Offline-mode players get name-based version 3 UUIDs and have no skin to look up.
        assertNull(PlayerSkins.sourceOf("e1b6c5e2-1f0a-3c4d-8e9f-0a1b2c3d4e5f"))
        assertNull(PlayerSkins.sourceOf("not-a-uuid"))
        assertNull(PlayerSkins.sourceOf(null))
    }

    @Test
    fun onlyMojangTexturesAreDownloaded() {
        val id = "4c4f4f8f3a7d5e1f0b2c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a"
        assertEquals("https://textures.minecraft.net/texture/$id", PlayerSkins.safeTextureUrl("http://textures.minecraft.net/texture/$id"))
        assertNull(PlayerSkins.safeTextureUrl("https://textures.minecraft.net.evil.example/texture/$id"))
        assertNull(PlayerSkins.safeTextureUrl("https://textures.minecraft.net/texture/../x"))
        assertNull(PlayerSkins.safeTextureUrl("file:///data/x"))
        assertNull(PlayerSkins.safeTextureUrl(null))
    }

    @Test
    fun faceUsesTheHatLayerOnTop() {
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        val skin = IntArray(64 * 64)
        for (y in 8 until 16) for (x in 8 until 16) skin[y * 64 + x] = red
        skin[8 * 64 + 40] = blue // one hat pixel over the top-left of the face
        val face = PlayerSkins.faceFrom(skin, 64, 64)!!
        assertEquals(blue, face[0])
        assertEquals(red, face[1])
        assertEquals(red, face[63])
    }

    @Test
    fun legacySkinsIgnoreASolidHatAndOddSizesAreRefused() {
        val red = 0xFFFF0000.toInt(); val black = 0xFF000000.toInt()
        val skin = IntArray(64 * 32)
        for (y in 8 until 16) {
            for (x in 8 until 16) skin[y * 64 + x] = red
            for (x in 40 until 48) skin[y * 64 + x] = black
        }
        assertEquals(red, PlayerSkins.faceFrom(skin, 64, 32)!![10])
        assertNull(PlayerSkins.faceFrom(IntArray(63 * 63), 63, 63))
        assertNull(PlayerSkins.faceFrom(IntArray(64 * 48), 64, 48))
    }
}
