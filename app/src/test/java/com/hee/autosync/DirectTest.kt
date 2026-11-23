package com.hee.autosync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.concurrent.thread

class DirectTest {
    private fun roundTrip(sendKey: String, data: ByteArray, dir: File): Pair<Boolean, File?> {
        val server = ServerSocket(0)
        var saved: File? = null
        val t = thread {
            server.accept().use { s ->
                saved = try { DirectProto.receive(s, "secret", dir, { Long.MAX_VALUE }, 0, { _, _ -> }, { _, _ -> }) } catch (e: Exception) { null }
            }
        }
        val ok = try {
            Socket("127.0.0.1", server.localPort).use { s ->
                val h = JSONObject().put("key", sendKey).put("from", "A").put("name", "p.jpg").put("size", data.size).put("modified", 1_000_000L)
                DirectProto.send(s, h, data.inputStream()) {}
            }
        } catch (e: Exception) { false }
        t.join(5_000); server.close()
        return ok to saved
    }

    @Test fun sendsAndSaves() {
        val dir = Files.createTempDirectory("d").toFile()
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val (ok, f) = roundTrip("secret", data, dir)
        assertTrue(ok)
        assertEquals("p.jpg", f!!.name)
        assertArrayEquals(data, f.readBytes())
        assertEquals(1_000_000L, f.lastModified())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") }) // 임시 파일 남지 않음
    }

    @Test fun wrongKeyRejected() {
        val dir = Files.createTempDirectory("d").toFile()
        val (ok, f) = roundTrip("nope", ByteArray(10), dir)
        assertTrue(!ok)
        assertNull(f)
        assertEquals(0, dir.listFiles()!!.size)
    }
}
