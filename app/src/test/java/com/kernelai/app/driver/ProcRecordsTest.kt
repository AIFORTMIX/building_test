package com.kernelai.app.driver

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcRecordsTest {
    @get:Rule val directory = TemporaryFolder()
    private fun stat(pid: Int = 321, start: Int = 100): String {
        val fields = MutableList(26) { "0" }; fields[0] = "S"; fields[19] = "$start"; fields[23] = "4096"; fields[24] = "8192"; fields[25] = "65536"
        return "$pid (fixture worker) " + fields.joinToString(" ")
    }
    private fun root(): File = directory.newFolder().also { root ->
        File(root, "321/stat").apply { parentFile.mkdirs(); writeText(stat()) }
        File(root, "sys/kernel/random/boot_id").apply { parentFile.mkdirs(); writeText("fixture-boot") }
        File(root, "321/maps").writeText((0..1300).joinToString("\n", postfix = "\n") { "${(4096L + it * 8192).toString(16)}-${(8192L + it * 8192).toString(16)} r-xp 00000000 00:00 1 /data/完整长路径/module-$it.so" })
    }
    @Test fun sourcePagingReadsBeyondNativeMapAndModuleLimitsWithFullPaths() {
        val root = root(); val reader = ProcRecords(root)
        for (kind in listOf("maps", "modules")) {
            val first = Json.parseToJsonElement(reader.read(321, kind, 0, 200, null, null)).jsonObject
            val tail = Json.parseToJsonElement(reader.read(321, kind, 1200, 200, first["process_identity"]!!.jsonPrimitive.content, first["revision"]!!.jsonPrimitive.content)).jsonObject
            assertEquals(1301, tail["total_count"]!!.jsonPrimitive.int)
            assertEquals(101, tail["records"]!!.jsonArray.size)
            assertEquals(JsonPrimitive(false), tail["has_more"])
            assertTrue(tail["records"]!!.jsonArray.last().jsonObject["path"]!!.jsonPrimitive.content.endsWith("module-1300.so"))
        }
    }
    @Test fun changingMappingsOrRestartingTargetRejectsOldContinuation() {
        val root = root(); val reader = ProcRecords(root)
        val first = Json.parseToJsonElement(reader.read(321, "maps", 0, 10, null, null)).jsonObject
        val identity = first["process_identity"]!!.jsonPrimitive.content; val revision = first["revision"]!!.jsonPrimitive.content
        File(root, "321/maps").appendText("900000-901000 rw-p 0000 00:00 0 [heap]\n")
        assertThrows(IllegalArgumentException::class.java) { reader.read(321, "maps", 10, 10, identity, revision) }
        File(root, "321/stat").writeText(stat(start = 200))
        assertThrows(IllegalArgumentException::class.java) { reader.read(321, "maps", 10, 10, identity, null) }
    }
    @Test fun threadPagesReachTheTailAndRejectInvalidInputs() {
        val root = root(); for (tid in 1..302) File(root, "321/task/$tid/stat").apply { parentFile.mkdirs(); writeText(stat(tid)) }
        val reader = ProcRecords(root)
        val tail = Json.parseToJsonElement(reader.read(321, "threads", 300, 100, null, null)).jsonObject
        assertEquals(2, tail["records"]!!.jsonArray.size)
        assertEquals(302, tail["records"]!!.jsonArray.last().jsonObject["tid"]!!.jsonPrimitive.int)
        assertThrows(IllegalArgumentException::class.java) { reader.read(-1, "maps", 0, 200, null, null) }
        assertThrows(IllegalArgumentException::class.java) { reader.read(321, "../stat", 0, 200, null, null) }
    }
}
