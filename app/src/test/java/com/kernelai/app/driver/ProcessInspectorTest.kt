package com.kernelai.app.driver

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessInspectorTest {
    @get:Rule val directory = TemporaryFolder()
    private fun stat(start: Int = 100, code: Int = 4096, state: String = "S"): String {
        val fields = MutableList(26) { "0" }; fields[0] = state; fields[19] = start.toString()
        fields[23] = code.toString(); fields[24] = "8192"; fields[25] = "65536"
        return "321 (worker ) helper) " + fields.joinToString(" ")
    }
    private fun prepare(boot: Boolean = true): File {
        val root = directory.newFolder()
        File(root, "321").mkdirs(); File(root, "321/stat").writeText(stat())
        File(root, "321/cmdline").writeText("com.example.test\u0000arg")
        if (boot) File(root, "sys/kernel/random/boot_id").apply { parentFile.mkdirs(); writeText("fixture-boot") }
        return root
    }
    @Test fun commContainingParenthesesDoesNotShiftIdentityFields() {
        val parsed = ProcessInspector.parseStat(stat(), 321)
        assertEquals("worker ) helper", parsed.comm); assertEquals("100", parsed.start)
    }
    @Test fun restartAndExecInvalidateIdentityButTaskSchedulingDoesNot() {
        val root = prepare(); val inspector = ProcessInspector(root)
        val original = inspector.read(321)
        assertTrue(original.alive); assertTrue(original.layoutObservable)
        File(root, "321/stat").writeText(stat(state = "R")); assertEquals(original.identity, inspector.read(321).identity)
        File(root, "321/stat").writeText(stat(start = 200)); assertNotEquals(original.identity, inspector.read(321).identity)
        File(root, "321/stat").writeText(stat(code = 16384)); assertNotEquals(original.identity, inspector.read(321).identity)
    }
    @Test fun missingOrZombieProcessIsNotUsable() {
        val root = prepare(); val inspector = ProcessInspector(root)
        File(root, "321/stat").writeText(stat(state = "Z")); assertFalse(inspector.read(321).alive)
        File(root, "321/stat").delete(); assertEquals("exited", inspector.read(321).state)
    }
    @Test fun missingBootIdentityOrBrokenStatIsUnknownRatherThanProofOfExit() {
        val root = prepare(boot = false); val inspector = ProcessInspector(root)
        assertEquals("unavailable", inspector.read(321).state)
        File(root, "321/stat").writeText("invalid")
        assertEquals("unavailable", inspector.read(321).state)
    }
}
