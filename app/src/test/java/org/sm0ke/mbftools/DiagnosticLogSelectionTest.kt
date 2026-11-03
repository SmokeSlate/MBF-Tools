package org.sm0ke.mbftools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogSelectionTest {
    @Test
    fun selectsNewestEightRatherThanFirstEightNames() {
        val files = (1..9).map { DiagnosticLogFile("/sdcard/logs/session-$it.log", it.toLong()) }
        assertEquals((2..9).map { "/sdcard/logs/session-$it.log" },
                DiagnosticLogSelection.selectNewest(files, 8))
    }

    @Test
    fun ranksByModificationTimeAcrossDirectoriesNotFilename() {
        val files = listOf(
                DiagnosticLogFile("/sdcard/logs/z.log", 10),
                DiagnosticLogFile("/sdcard/logs/a.log", 30),
                DiagnosticLogFile("/sdcard/logs2/b.log", 20))
        assertEquals(listOf("/sdcard/logs2/b.log", "/sdcard/logs/a.log"),
                DiagnosticLogSelection.selectNewest(files, 2))
    }

    @Test
    fun aliasesDoNotConsumeFileLimitButDistinctDirectoriesRemain() {
        val files = listOf(
                DiagnosticLogFile("/sdcard/logs/current.log", 30),
                DiagnosticLogFile("/storage/emulated/0/logs/current.log", 30),
                DiagnosticLogFile("/sdcard/logs2/current.log", 20),
                DiagnosticLogFile("/sdcard/logs/old.log", 10))
        assertEquals(listOf("/sdcard/logs2/current.log", "/sdcard/logs/current.log"),
                DiagnosticLogSelection.selectNewest(files, 2))
    }

    @Test
    fun parsesStatTimesAndPathsWithSpacesIgnoringMalformedLines() {
        assertEquals(listOf(DiagnosticLogFile("/sdcard/logs/session one.LOG", 123)),
                DiagnosticLogSelection.parseStatOutput(
                        "123 /sdcard/logs/session one.LOG\r\ninvalid\nnope /sdcard/a.log\n123 /sdcard/other.txt\n"))
        assertTrue(DiagnosticLogSelection.selectNewest(emptyList(), 8).isEmpty())
    }

    @Test
    fun newestSessionSurvivesTotalLineCap() {
        val files = (1..9).map { DiagnosticLogFile("/sdcard/logs/session-$it.log", it.toLong()) }
        val selected = DiagnosticLogSelection.selectNewest(files, 8)
        val lines = selected.flatMap { path -> (1..60).map { "$path line $it" } }.takeLast(400)
        assertTrue(lines.any { it.contains("session-9.log") })
        assertEquals("/sdcard/logs/session-9.log line 60", lines.last())
    }
    @Test
    fun quotesStatFormatAndUntrustedFilenamesForAdbShell() {
        assertEquals("'%Y %n'", DiagnosticLogSelection.shellQuote("%Y %n"))
        assertEquals("'/sdcard/session one.log'",
                DiagnosticLogSelection.shellQuote("/sdcard/session one.log"))
        assertEquals("'session'\\''s $(false).log'",
                DiagnosticLogSelection.shellQuote("session's $(false).log"))
    }

}
