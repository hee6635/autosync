package com.hee.autosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RulesTest {
    private val mb = 1024L * 1024

    @Test fun skipReason() {
        assertNull(skipReason("PXL_1.jpg", 5 * mb))
        assertNull(skipReason("v.MP4", 5000 * mb))
        assertEquals("형식 미지원", skipReason("a.zip", mb))
        assertEquals("숨김/임시 파일", skipReason(".pending-1.jpg", mb))
        assertEquals("사진 200MB 초과", skipReason("big.jpg", 201 * mb))
        assertEquals("크기 0", skipReason("e.jpg", 0))
    }

    @Test fun safeName() {
        assertEquals("a.jpg", safeName("a.jpg"))
        assertEquals(".._.._x.jpg".let { "_$it" }, safeName("../../x.jpg"))
        assertEquals("_.hidden", safeName(".hidden"))
        assertEquals("a_b.jpg", safeName("a/b.jpg"))
    }

    @Test fun uniqueFile() {
        val dir = Files.createTempDirectory("u").toFile()
        File(dir, "p.jpg").writeText("x")
        File(dir, "p_1.jpg").writeText("x")
        assertEquals("p_2.jpg", uniqueFile(dir, "p.jpg").name)
        assertEquals("n.mp4", uniqueFile(dir, "n.mp4").name)
    }

    @Test fun inWindow() {
        assertTrue(inWindow(100, -1, -1))           // 예약 없음
        assertTrue(inWindow(130, 120, 360))         // 02:10, 02:00~06:00
        assertFalse(inWindow(360, 120, 360))        // 끝 시각은 밖
        assertTrue(inWindow(23 * 60, 22 * 60, 360)) // 자정 넘는 시간대
        assertTrue(inWindow(60, 22 * 60, 360))
        assertFalse(inWindow(12 * 60, 22 * 60, 360))
    }

    @Test fun uniqueFileTaken() {
        val dir = Files.createTempDirectory("t").toFile()
        assertEquals("a_1.jpg", uniqueFile(dir, "a.jpg", setOf("a.jpg")).name)
    }

    @Test fun md5() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5("abc".byteInputStream()))
    }
}
