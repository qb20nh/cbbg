package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class FabricRuntimeInstallationTest {
    @TempDir File directory
    private static final String TARGET = '26.3-fabric'
    private static final String PROFILE = 'fabric-loader-0.19.5-26.3'

    private static void receipt(File runtime, Map changes = [:]) {
        runtime.mkdirs()
        new File(runtime, 'cbbg-install-receipt.json').text = JsonOutput.toJson(
                [target: TARGET, profile: PROFILE, installed: false] + changes)
    }

    @Test void resumesAFailedInstallThenReusesTheCompletedRuntime() {
        File runtime = new File(directory, 'runtimes/0.19.5')
        List<Boolean> attempts = []
        Closure<Integer> installer = { boolean resume ->
            attempts.add(resume)
            receipt(runtime, [installed: resume])
            resume ? 0 : 1
        }
        assertThrows(GradleException) { FabricRuntimeInstallation.ensure(runtime, TARGET, PROFILE, installer) }
        FabricRuntimeInstallation.ensure(runtime, TARGET, PROFILE, installer)
        FabricRuntimeInstallation.ensure(runtime, TARGET, PROFILE, installer)
        assertEquals([false, true], attempts)
    }

    @Test void installsANewRuntimeAndChecksItsCompletion() {
        File runtime = new File(directory, 'runtimes/0.19.5')
        FabricRuntimeInstallation.ensure(runtime, TARGET, PROFILE) { boolean resume ->
            assertFalse(resume)
            assertTrue(runtime.parentFile.isDirectory())
            receipt(runtime, [installed: true])
            0
        }
        File incomplete = new File(directory, 'incomplete')
        assertThrows(GradleException) {
            FabricRuntimeInstallation.ensure(incomplete, TARGET, PROFILE) { receipt(incomplete); 0 }
        }
    }

    @Test void rejectsAnExistingDirectoryWithoutAMatchingReceipt() {
        File runtime = new File(directory, 'runtime')
        runtime.mkdirs()
        Closure<Integer> installer = { fail('Invalid runtime must not be installed over'); 0 }
        assertThrows(GradleException) { FabricRuntimeInstallation.ensure(runtime, TARGET, PROFILE, installer) }
        for (Map changes : [[target: '26.2-fabric'], [profile: 'other'], [installed: 'false']]) {
            receipt(runtime, changes)
            assertThrows(GradleException) { FabricRuntimeInstallation.ensure(runtime, TARGET, PROFILE, installer) }
        }
    }
}
