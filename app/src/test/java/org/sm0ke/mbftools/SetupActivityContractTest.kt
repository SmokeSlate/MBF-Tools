package org.sm0ke.mbftools

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source architecture checks, not substitutes for device/UI instrumentation. */
class SetupActivityContractTest {
    private fun source(name: String): String {
        val path = "src/main/java/org/sm0ke/mbftools/$name.kt"
        return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
    }

    private fun method(source: String, name: String): String =
        source.substringAfter("private fun $name(").substringBefore("\n    private fun ")

    @Test fun postConnectAdbWorkStaysBeforeUiDispatchAndUsesCaughtResult() {
        for ((activity, name) in listOf("MainActivity" to "connectToDevice", "GuideActivity" to "pairDevice")) {
            val flow = method(source(activity), name)
            val worker = flow.substringBefore("runOnUiThread {")
            val ui = flow.substringAfter("runOnUiThread {")
            assertTrue(worker.contains("connectAttempt.result.mapCatching"))
            assertTrue(worker.contains("AdbManager.getAuthorizedDevices"))
            assertTrue(worker.contains("AdbManager.grantSelfPermissions"))
            assertFalse(ui.contains("AdbManager.getAuthorizedDevices"))
            assertFalse(ui.contains("AdbManager.grantSelfPermissions"))
            assertTrue(ui.contains("connectionResult"))
            assertTrue(ui.contains("onFailure"))
        }
    }

    @Test fun everyExplicitGuideEntryRequestsRevisit() {
        for (activity in listOf("HomeActivity", "DiagnoseActivity", "WirelessDebugRequiredActivity")) {
            val launch = source(activity).substringAfter("Intent(this, GuideActivity::class.java)")
            assertTrue(launch.trimStart().startsWith(".putExtra(GuideActivity.EXTRA_REVISIT_SETUP, true)"))
        }
    }

    @Test fun pollingUsesTheBusyPolicy() {
        val home = method(source("HomeActivity"), "refreshState")
        assertTrue(home.contains("launchMbfButton.isEnabled = SetupFlowPolicy.actionEnabled(actionBusy, connected)"))
        assertTrue(home.contains("shareDebugLogsButton.isEnabled = SetupFlowPolicy.actionEnabled(actionBusy)"))
        val main = method(source("MainActivity"), "refreshState")
        assertTrue(main.contains("launchMbfButton.isEnabled = SetupFlowPolicy.actionEnabled(actionBusy)"))
        assertTrue(main.contains("deviceSettingButtons.forEach { it.isEnabled = SetupFlowPolicy.actionEnabled(actionBusy) }"))
        val guide = method(source("GuideActivity"), "updateNextAvailability")
        assertTrue(guide.contains("SetupFlowPolicy.actionEnabled(actionBusy, isNextStepAvailable())"))
    }
}
